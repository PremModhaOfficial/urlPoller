# URL Poller Architecture

## High-Level System Architecture

```mermaid
flowchart TB
    subgraph Main["Main Entry Point"]
        A[Main.java<br/>Application Bootstrap]
    end
    
    subgraph Verticles["Core Verticles"]
        B[Distributor<br/>Task Scheduler & IP Manager]
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
    B -->|Groups IPs by Interval<br/>Creates Timers| H
    H -->|TIMER_EXPIRED<br/>Every N seconds| B
    
    B -->|Batch IPs| D
    D -->|Executes fping process<br/>All IPs in batch| D
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
    D->>D: Create periodic timers<br/>for each interval
    
    loop Every N seconds (per interval group)
        D->>EB: Publish TIMER_EXPIRED(interval)
        EB->>D: Receive TIMER_EXPIRED
        D->>FBW: Execute batch ping<br/>(all IPs in interval)
        
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
    TimerCreation --> WaitingForTimer: Periodic timers set
    
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
        10s-1000s of IPs concurrently
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

### 3. **Async/Non-Blocking**
- `Process.onExit()` for non-blocking process wait
- CompletableFuture for async composition
- Vert.x async file I/O

### 4. **Concurrent Parsing**
- Parallel streams for parsing fping output
- ConcurrentHashMap for thread-safe result collection
- Scales to 1000+ IPs per batch

### 5. **Thread-Safe Design**
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
