#!/usr/bin/env nu

# URL Poller Interval Verification Script
# Verifies that all IPs are polling at their configured intervals

# Configuration constants
const STATS_DIR = "stats"
const CONFIG_FILE = "urls.txt"
const ACCURATE_THRESHOLD = 0.05  # 5% variance = ACCURATE
const WARNING_THRESHOLD = 0.15   # 15% variance = WARNING

# Main entry point
def main [] {
    print "🔍 URL Poller Interval Verification Script"
    print $"Configuration: ($CONFIG_FILE)"
    print $"Stats directory: ($STATS_DIR)"

    # Load IP configurations
    print "\n📋 Loading IP configurations..."
    let config = load-config $CONFIG_FILE
    print $"✅ Loaded ($config | length) IP configurations"
    print $"First config: ($config | first)"

    # Apply filters (none for now)
    let filtered_config = $config
    print $"📊 After filtering: ($filtered_config | length) IPs to verify"

    # Analyze polling intervals
    print "\n⏳ Analyzing polling intervals..."
    let results = $filtered_config | par-each -t 10 {|entry|
        try {
            analyze-ip $entry.ip $entry.expected_interval $STATS_DIR
        } catch {|err|
            {
                ip: $entry.ip,
                expected_interval: $entry.expected_interval,
                csv_file: "",
                csv_exists: false,
                sample_count: 0,
                status: "ERROR",
                message: $err.msg,
                mean_interval: null,
                std_deviation: null,
                variance_percent: null,
                first_poll: null,
                last_poll: null
            }
        }
    }

    # Generate report
    print "\n📈 Generating report..."
    generate-report $results false

    # Export results
    export-results $results "json"

    print "\n✅ Verification complete!"
}

# Load configuration from urls.txt
def load-config [file: string] {
    if not ($file | path exists) {
        error make {msg: $"Configuration file not found: ($file)"}
    }

    open $file
    | lines
    | where {|line| not ($line | str starts-with '#')}  # Skip comments
    | where {|line| ($line | str trim) != ''}           # Skip empty lines
    | where {|line| $line =~ ','}                        # Must contain comma
    | each {|line|
        # Handle both comma and tab separators
        let parts = if $line =~ "\t" {
            $line | split row "\t" | where {|p| $p != ""}
        } else {
            $line | split row ","
        }

        if ($parts | length) < 2 {
            print $"⚠️  Skipping malformed line: ($line)"
            null
        } else {
            {
                ip: ($parts | first | str trim),
                expected_interval: ($parts | last | str trim | into int)
            }
        }
    }
    | where {|row| $row != null}
    | sort-by ip
}

# Apply filters to configuration
def apply-filters [config: list<record>, max_interval: int, ips: string] {
    let filtered = $config

    # Filter by maximum interval
    let filtered = if $max_interval != null {
        $filtered | where expected_interval <= $max_interval
    } else {
        $filtered
    }

    # Filter by specific IPs
    let filtered = if $ips != null {
        let ip_list = $ips | split row "," | each {|ip| $ip | str trim}
        $filtered | where {|row| $row.ip in $ip_list}
    } else {
        $filtered
    }

    $filtered
}

# Analyze polling intervals for a single IP
def analyze-ip [ip: string, expected: int, stats_dir: string] {
    let csv_path = $"($stats_dir)/($ip).csv"

    # Check if CSV file exists
    if not ($csv_path | path exists) {
        return {
            ip: $ip,
            expected_interval: $expected,
            csv_file: $csv_path,
            csv_exists: false,
            sample_count: 0,
            status: "MISSING",
            message: "CSV file not found",
            mean_interval: null,
            std_deviation: null,
            variance_percent: null,
            first_poll: null,
            last_poll: null
        }
    }

    # Read CSV file
    let raw_content = try {
        open $csv_path --raw
    } catch {|err|
        return {
            ip: $ip,
            expected_interval: $expected,
            csv_file: $csv_path,
            csv_exists: true,
            sample_count: 0,
            status: "PARSE_ERROR",
            message: $"Failed to read CSV file: ($err.msg)",
            mean_interval: null,
            std_deviation: null,
            variance_percent: null,
            first_poll: null,
            last_poll: null
        }
    }

    let raw_data = $raw_content | lines

    # Parse CSV manually (skip header, split by comma)
    let data = $raw_data
    | skip 1  # Skip header
    | each {|line|
        let parts = $line | split row ","
        if ($parts | length) >= 2 {
            {Timestamp: ($parts | get 0), EpochMs: ($parts | get 1), IP: ($parts | get 2), Status: ($parts | get 3), PacketLoss: ($parts | get 4), MinRTT_ms: ($parts | get 5), AvgRTT_ms: ($parts | get 6), MaxRTT_ms: ($parts | get 7)}
        }
    }
    | where {|row| $row != null}

    # Extract epochs and calculate intervals
    let epochs = $data | get EpochMs | each {|e| $e | into int}
    let intervals = calculate-intervals $epochs

    # Calculate statistics
    let mean = $intervals | math avg
    let stddev = $intervals | math stddev
    let min_val = $intervals | math min
    let max_val = $intervals | math max
    let variance = ($mean - $expected) | math abs
    let variance_pct = $variance / $expected

    # Determine status
    let status = if $variance_pct <= $ACCURATE_THRESHOLD {
        "ACCURATE"
    } else if $variance_pct <= $WARNING_THRESHOLD {
        "WARNING"
    } else {
        "ERROR"
    }

    # Return complete analysis
    {
        ip: $ip,
        expected_interval: $expected,
        csv_file: $csv_path,
        csv_exists: true,
        sample_count: ($data | length),
        actual_intervals: $intervals,
        mean_interval: $mean,
        std_deviation: $stddev,
        min_interval: $min_val,
        max_interval: $max_val,
        variance: $variance,
        variance_percent: ($variance_pct * 100),
        status: $status,
        first_poll: ($data | first | get Timestamp),
        last_poll: ($data | last | get Timestamp)
    }
}

# Calculate intervals between consecutive epochs (in seconds)
def calculate-intervals [epochs: list<int>] {
    if ($epochs | length) < 2 {
        return []
    }

    mut intervals = []
    for i in 0..(($epochs | length) - 2) {
        let diff = (($epochs | get ($i + 1)) - ($epochs | get $i)) / 1000.0
        $intervals = ($intervals | append $diff)
    }
    $intervals
}

# Generate comprehensive report
def generate-report [results: list<record>, verbose: bool] {
    print "\n╔═══════════════════════════════════════════════════════════════╗"
    print "║           POLLING INTERVAL VERIFICATION REPORT                ║"
    print "╚═══════════════════════════════════════════════════════════════╝"

    # Summary statistics
    let total = $results | length
    let accurate = $results | where status == "ACCURATE" | length
    let warnings = $results | where status == "WARNING" | length
    let errors = $results | where status == "ERROR" | length
    let missing = $results | where status == "MISSING" | length
    let insufficient = $results | where status == "INSUFFICIENT_DATA" | length
    let parse_errors = $results | where status == "PARSE_ERROR" | length

    print $"\n📊 SUMMARY STATISTICS:"
    print $"Total IPs analyzed: ($total)"
    print $"  ✅ ACCURATE:         ($accurate) (($accurate * 100 / $total | math round --precision 1)%)"
    print $"  ⚠️  WARNING:          ($warnings) (($warnings * 100 / $total | math round --precision 1)%)"
    print $"  ❌ ERROR:            ($errors) (($errors * 100 / $total | math round --precision 1)%)"
    print $"  🚫 MISSING:          ($missing) (($missing * 100 / $total | math round --precision 1)%)"
    print $"  📊 INSUFFICIENT:     ($insufficient) (($insufficient * 100 / $total | math round --precision 1)%)"
    print $"  🔧 PARSE ERROR:      ($parse_errors) (($parse_errors * 100 / $total | math round --precision 1)%)"

    # Overall accuracy metrics
    if ($accurate + $warnings + $errors) > 0 {
        let valid_results = $results | where {|r| $r.status in ["ACCURATE", "WARNING", "ERROR"]}
        let avg_variance = $valid_results | get variance_percent | math avg
        let max_variance = $valid_results | get variance_percent | math max
        let min_variance = $valid_results | get variance_percent | math min

        print $"\n📈 ACCURACY METRICS:"
        print $"Average variance: ($avg_variance | math round --precision 2)%"
        print $"Min variance: ($min_variance | math round --precision 2)%"
        print $"Max variance: ($max_variance | math round --precision 2)%"
    }

    # Detailed reports for issues
    if $errors > 0 {
        print "\n❌ IPs WITH ERRORS (variance > 15%):"
        print "─────────────────────────────────────────────────────────────"
        $results
        | where status == "ERROR"
        | select ip expected_interval mean_interval variance_percent sample_count
        | each {|row|
            print $"  • ($row.ip): Expected ($row.expected_interval)s, Got ($row.mean_interval | math round --precision 2)s (±($row.variance_percent | math round --precision 2)%), Samples: ($row.sample_count)"
        }
    }

    if $warnings > 0 {
        print "\n⚠️  IPs WITH WARNINGS (variance 5-15%):"
        print "─────────────────────────────────────────────────────────────"
        $results
        | where status == "WARNING"
        | select ip expected_interval mean_interval variance_percent sample_count
        | each {|row|
            print $"  • ($row.ip): Expected ($row.expected_interval)s, Got ($row.mean_interval | math round --precision 2)s (±($row.variance_percent | math round --precision 2)%), Samples: ($row.sample_count)"
        }
    }

    if $missing > 0 {
        print "\n🚫 MISSING CSV FILES:"
        print "─────────────────────────────────────────────────────────────"
        $results
        | where status == "MISSING"
        | select ip expected_interval
        | each {|row|
            print $"  • ($row.ip) (expected interval: ($row.expected_interval)s)"
        }
    }

    if $parse_errors > 0 {
        print "\n🔧 CSV PARSE ERRORS:"
        print "─────────────────────────────────────────────────────────────"
        $results
        | where status == "PARSE_ERROR"
        | select ip message
        | each {|row|
            print $"  • ($row.ip): ($row.message)"
        }
    }

    # Show top 10 most accurate (if we have enough)
    let accurate_ips = $results | where status == "ACCURATE" | where variance_percent != null
    if ($accurate_ips | length) > 0 {
        print "\n🎯 TOP 10 MOST ACCURATE IPs:"
        print "─────────────────────────────────────────────────────────────"
        let top10 = $accurate_ips | sort-by variance_percent | first 10
        for row in $top10 {
            let variance = $row.variance_percent | math round --precision 3
            let mean = $row.mean_interval | math round --precision 3
            print $"  • ($row.ip): ($row.expected_interval)s -> ($mean)s variance ($variance)%"
        }
    }

    # Verbose mode: show all results in table format
    if $verbose {
        print "\n📋 DETAILED RESULTS (all IPs):"
        print "─────────────────────────────────────────────────────────────"
        $results
        | select ip expected_interval status sample_count mean_interval variance_percent
        | table -e
    }
}

# Export results to file
def export-results [results: list<record>, format: string] {
    let timestamp = date now | format date "%Y%m%d_%H%M%S"
    let filename = $"verify_results_($timestamp)"

    match $format {
        "json" => {
            $results | to json | save $"($filename).json"
            print $"💾 Results saved to ($filename).json"
        }
        "csv" => {
            $results | to csv | save $"($filename).csv"
            print $"💾 Results saved to ($filename).csv"
        }
        "table" => {
            print "📋 Results displayed in console only (no file export)"
        }
        _ => {
            print $"⚠️  Unknown output format: ($format). Use json, csv, or table."
        }
    }
}

# Run main function
main