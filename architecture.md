# URL Poller Architecture

## High-Level System Architecture

```mermaid
flowchart TB
    subgraph Main["Main Entry Point"]
        A[Main.java<br/>Application Bootstrap]
    end
    
    subgraph Verticles["Core Verticles"]
        B[Distributor<br/>Task Scheduler & IP Manager<br/>Phase-Shifted Sub-Groups]
        C[FileWriter<br/>CSV Output Handler]
    end
    
    subgraph Workers["Worker Layer"]
        D[FpingBatchWorker<br/>Batch Ping Executor]
        E[FpingResultParser<br/>Output Parser]
    end
    
    subgraph Models["Data Models"]
        F[PingResult<br/>Result Data Object]
        G[JsonFields<br/>Constants]
    end
    
    subgraph EventBus["Vert.x Event Bus"]
        H{Event System}
    end
    
    subgraph Storage["File System"]
        I[(stats/ Directory<br/>CSV Files per IP)]
        J[(urls.txt<br/>Configuration)]
    end
    
    A -->|1. Deploys Verticles| B
    A -->|2. Deploys Verticles| C
    A -->|3. Reads Config| J
    A -->|4. Publishes| H
    
    H -->|CONFIG_LOADED| B
    B -->|Groups IPs by Interval<br/>Splits into Sub-Groups<br/>Creates Phase-Shifted Timers| H
    H -->|TIMER_EXPIRED<br/>Every N seconds<br/>with phase offset| B
    
    B -->|Batch IPs<br/>from Sub-Group| D
    D -->|Executes fping process<br/>All IPs in sub-group| D
    D -->|Parses Output| E
    E -->|Creates| F
    
    D -->|PROCESS_SUCCEEDED<br/>PROCESS_FAILED| H
    H -->|Result Events| C
    
    C -->|Writes CSV Rows<br/>with Timestamp| I
    
    F -.->|Used by| E
    F -.->|Used by| D
    G -.->|Used by| B
    G -.->|Used by| C
    G -.->|Used by| D

    style A fill:#ff9999,stroke:#333,stroke-width:3px
    style H fill:#99ccff,stroke:#333,stroke-width:3px
    style D fill:#99ff99,stroke:#333,stroke-width:2px
    style I fill:#ffff99,stroke:#333,stroke-width:2px
```

## Detailed Component Flow

```mermaid
sequenceDiagram
    participant M as Main.java
    participant FS as FileSystem
    participant EB as Event Bus
    participant D as Distributor
    participant FBW as FpingBatchWorker
    participant FRP as FpingResultParser
    participant FW as FileWriter
    participant CSV as CSV Files

    M->>M: Create Vertx instance<br/>(4 event loops, 2 worker threads)
    M->>D: Deploy Distributor verticle
    M->>FW: Deploy FileWriter verticle
    M->>FS: Read urls.txt
    FS-->>M: File content
    M->>EB: Publish CONFIG_LOADED event
    
    EB->>D: Receive CONFIG_LOADED
    D->>D: Parse IPs & intervals<br/>(e.g., 8.8.8.8,5 → 5s interval)
    D->>D: Group IPs by interval<br/>Map<Byte, Set<String>>
    D->>D: Split large groups (>1000 IPs)<br/>into phase-shifted sub-groups
    D->>D: Create periodic timers<br/>with phase offsets per sub-group
    
    loop Every N seconds (per interval group, phase-shifted)
        D->>EB: Publish TIMER_EXPIRED(interval, subGroupIndex)
        EB->>D: Receive TIMER_EXPIRED
        D->>FBW: Execute batch ping<br/>(IPs from sub-group)
        
        FBW->>FBW: Build fping command<br/>fping -c 1 -t 2000 -q IP1 IP2...
        FBW->>FBW: Start Process
        FBW->>FBW: Wait async (Process.onExit())
        FBW-->>FBW: Process completed
        FBW->>FRP: Parse output<br/>(parallel stream)
        
        FRP->>FRP: Regex pattern matching<br/>per line (concurrent)
        FRP-->>FBW: Map<IP, PingResult>
        
        loop For each IP result
            FBW->>EB: Publish PROCESS_SUCCEEDED<br/>or PROCESS_FAILED
            EB->>FW: Receive result event
            FW->>FW: Format CSV row<br/>Timestamp,Epoch,IP,Status,Loss,RTT...
            FW->>CSV: Append to stats/IP.csv
        end
    end
```

## Event-Driven Architecture

```mermaid
stateDiagram-v2
    [*] --> Initialization
    
    Initialization --> ConfigLoading: Main starts
    ConfigLoading --> IPGrouping: CONFIG_LOADED event
    
    IPGrouping --> TimerCreation: Group by interval
    TimerCreation --> SubGroupSplitting: Split large groups (>1000 IPs)
    SubGroupSplitting --> WaitingForTimer: Phase-shifted timers set
    
    WaitingForTimer --> BatchExecution: TIMER_EXPIRED
    
    state BatchExecution {
        [*] --> BuildCommand
        BuildCommand --> ExecuteFping
        ExecuteFping --> WaitProcess
        WaitProcess --> ParseOutput
        ParseOutput --> PublishResults
        PublishResults --> [*]
    }
    
    BatchExecution --> FileWriting: PROCESS_SUCCEEDED/FAILED
    
    state FileWriting {
        [*] --> CheckHeader
        CheckHeader --> WriteHeader: First write
        CheckHeader --> WriteRow: Header exists
        WriteHeader --> WriteRow
        WriteRow --> FlushClose
        FlushClose --> [*]
    }
    
    FileWriting --> WaitingForTimer: Next cycle
    BatchExecution --> WaitingForTimer: Timeout/Error

    note right of BatchExecution
        Single fping process handles
        up to 1000 IPs per sub-group
        Large groups split with phase offsets
        High performance, low thread count
    end note
    
    note right of FileWriting
        Async file I/O
        One CSV file per IP
        Thread-safe operations
    end note
```

## Thread Model & Concurrency

```mermaid
flowchart LR
    subgraph VertxEventLoop["Vert.x Event Loop Pool (4 threads)"]
        EL1[Event Loop 1]
        EL2[Event Loop 2]
        EL3[Event Loop 3]
        EL4[Event Loop 4]
    end
    
    subgraph WorkerPool["Internal Blocking Pool (2 threads)"]
        W1[Worker 1]
        W2[Worker 2]
    end
    
    subgraph FpingThreads["FpingBatchWorker Thread Pool (Cached)"]
        FT1[fping-batch-handler-1]
        FT2[fping-batch-handler-2]
        FT3[fping-batch-handler-N]
    end
    
    subgraph Processes["OS Processes"]
        P1[fping process<br/>5s interval batch]
        P2[fping process<br/>10s interval batch]
        P3[fping process<br/>30s interval batch]
    end
    
    EL1 --> |Timer fires| EL1
    EL1 --> |Spawn async| FT1
    EL2 --> |Event handling| EL2
    
    FT1 --> |Execute| P1
    FT2 --> |Execute| P2
    FT3 --> |Execute| P3
    
    P1 --> |Results| FT1
    FT1 --> |Parse parallel| FT1
    FT1 --> |Publish to EventBus| EL3
    
    EL3 --> |File write| W1
    W1 --> |Async I/O| W1

    style VertxEventLoop fill:#e1f5ff,stroke:#333
    style WorkerPool fill:#fff4e1,stroke:#333
    style FpingThreads fill:#e8f5e9,stroke:#333
    style Processes fill:#f3e5f5,stroke:#333
```

## Data Flow & Performance

```mermaid
flowchart TD
    subgraph Input["Input Configuration"]
        A1[urls.txt<br/>IP,Interval per line<br/>Example: 8.8.8.8,5]
    end
    
    subgraph Processing["Processing Pipeline"]
        B1[Parse & Group<br/>5s: 300 IPs<br/>10s: 500 IPs<br/>30s: 200 IPs]
        B2[Batch Execution<br/>fping -c 1 -t 2000 -q<br/>IP1 IP2 IP3...]
        B3[Concurrent Parsing<br/>Parallel Stream<br/>Regex Matching]
    end
    
    subgraph Output["Output"]
        C1[CSV Files<br/>stats/8.8.8.8.csv<br/>stats/1.1.1.1.csv<br/>One file per IP]
    end
    
    subgraph Performance["Performance Metrics"]
        D1[Thread Efficiency<br/>~10 threads for 1000 IPs<br/>vs 2000 with individual ping]
        D2[Batch Size<br/>100-1000 IPs per batch<br/>Single fping process]
        D3[Latency<br/>~2-3s for 1000 IPs<br/>Parallel parsing]
    end
    
    A1 --> B1
    B1 --> B2
    B2 --> B3
    B3 --> C1
    
    B2 -.->|Optimized by| D1
    B2 -.->|Scales to| D2
    B3 -.->|Achieves| D3

    style Performance fill:#c8e6c9,stroke:#2e7d32,stroke-width:2px
```

## Key Design Patterns

### 1. **Event-Driven Architecture**
- Loose coupling via Event Bus
- 4 event types: `CONFIG_LOADED`, `TIMER_EXPIRED`, `PROCESS_SUCCEEDED`, `PROCESS_FAILED`

### 2. **Batch Processing Pattern**
- Groups IPs by interval
- Single `fping` process handles multiple IPs
- Reduces thread count from O(n) to O(intervals)

### 3. **Phase-Shifted Load Distribution** ⭐ NEW
- Splits large groups (>1000 IPs) into sub-groups
- Each sub-group fires at phase-shifted intervals
- **Example:** 10,000 IPs at 10s interval:
  - Sub-group 1: fires at 0s, 10s, 20s... (1000 IPs)
  - Sub-group 2: fires at 1s, 11s, 21s... (1000 IPs)
  - Sub-group 3: fires at 2s, 12s, 22s... (1000 IPs)
  - ... (10 sub-groups total)
- **Benefits:**
  - Distributes CPU load evenly across time
  - Prevents thread pool saturation
  - Reduces memory spikes
  - Maintains polling accuracy per IP

### 4. **Async/Non-Blocking**
- `Process.onExit()` for non-blocking process wait
- CompletableFuture for async composition
- Vert.x async file I/O

### 5. **Concurrent Parsing**
- Parallel streams for parsing fping output
- ConcurrentHashMap for thread-safe result collection
- Scales to 1000+ IPs per batch

### 6. **Thread-Safe Design**
- Immutable PingResult objects
- ConcurrentHashMap for shared state
- Atomic operations (ConcurrentHashMap.newKeySet)

## Technology Stack

```mermaid
mindmap
  root((URL Poller))
    Vert.x Framework
      Event Bus
      Async File I/O
      Verticles
      Event Loop Model
    Java 17
      Records potential
      Process API
      CompletableFuture
      Parallel Streams
    External Tools
      fping
        Batch ping
        Fast ICMP
        Low overhead
    Concurrency
      ConcurrentHashMap
      Parallel Streams
      Thread Pools
      Atomic Operations
```

## Phase-Shifted Timer Architecture (Load Balancing)

### Problem: GCD Clustering
When many IPs share common divisor poll times (10s, 20s, 30s, 40s...), they all group at the GCD (10s), creating massive timer groups that fire simultaneously.

### Solution: Sub-Group Phase Shifting

```mermaid
gantt
    title Load Distribution: 5000 IPs at 10s Interval (Split into 5 Sub-Groups)
    dateFormat ss
    axisFormat %S
    
    section Sub-Group 1
    Batch 1 (1000 IPs): 00, 10s
    
    section Sub-Group 2
    Batch 2 (1000 IPs): 02, 10s
    
    section Sub-Group 3
    Batch 3 (1000 IPs): 04, 10s
    
    section Sub-Group 4
    Batch 4 (1000 IPs): 06, 10s
    
    section Sub-Group 5
    Batch 5 (1000 IPs): 08, 10s
```

### Data Structure Evolution

**Before (Single Group):**
```java
Map<Byte, Set<String>> ipTable;
// Example: {10 -> Set[5000 IPs]}
// Result: Single timer fires every 10s with 5000 IPs
```

**After (Sub-Groups with Phase Offsets):**
```java
Map<Byte, List<Set<String>>> ipTable;
// Example: {10 -> [
//   Set[1000 IPs], // offset=0s
//   Set[1000 IPs], // offset=2s
//   Set[1000 IPs], // offset=4s
//   Set[1000 IPs], // offset=6s
//   Set[1000 IPs]  // offset=8s
// ]}
// Result: 5 timers, each fires every 10s with 1000 IPs, staggered by 2s
```

### Timer Firing Timeline

```
Time (seconds): 0----2----4----6----8----10---12---14---16---18---20
Sub-Group 1:    ■---------■---------■----------■----------■----------■
Sub-Group 2:    --■---------■---------■----------■----------■--------
Sub-Group 3:    ----■---------■---------■----------■----------■------
Sub-Group 4:    ------■---------■---------■----------■----------■----
Sub-Group 5:    --------■---------■---------■----------■----------■--

Legend: ■ = fping batch execution (1000 IPs, ~2s duration)
```

### Configuration

- **MAX_IPS_PER_TIMER_GROUP**: 1000 (default)
  - Adjustable based on system capacity
  - Lower value = more sub-groups = smoother load distribution
  - Higher value = fewer sub-groups = fewer timers

- **Phase Offset Calculation**:
  ```
  phaseOffsetStep = pollInterval / numSubGroups
  initialDelay[i] = phaseOffsetStep * i
  ```

### Benefits

| Metric | Before Splitting | After Splitting (5 groups) |
|--------|------------------|----------------------------|
| Max IPs per batch | 5,000 | 1,000 |
| Timer collisions | High (all at GCD) | None (phase-shifted) |
| CPU usage pattern | Spiky (100% then idle) | Smooth (60-80% sustained) |
| Thread pool saturation | Yes (all workers busy) | No (1-2 workers busy) |
| Memory pressure | High (5k process args) | Low (1k process args) |
| fping process duration | ~4-6 seconds | ~1-2 seconds |

