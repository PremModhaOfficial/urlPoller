# Progress Report Generator - Intelligent Command Strategy

Generate a detailed progress report in an org file format from the perspective of a trainee software engineer.

## CRITICAL: Context-Efficient Workflow

This workflow is designed to minimize context usage by avoiding large diffs from data files (JSON, logs, etc.) and focusing only on meaningful source code changes.

---

## Step 1: Get Today's Commits (Date-Based Filtering)

**IMPORTANT:** Use exact date filtering, not "today" to avoid including previous days' commits.

### Command to Execute:
```bash
cd /home/prem-modha/projects/Motadata/verts/urlPoller

# Get today's date in ISO format
TODAY=$(date +%Y-%m-%d)

# Option 1: Using jj (Jujutsu VCS) - PREFERRED
jj log --no-graph --after "${TODAY}T00:00:00" --before "${TODAY}T23:59:59"

# Option 2: Using git (fallback)
git log --since="${TODAY} 00:00:00" --until="${TODAY} 23:59:59" --pretty=format:"%H|%an|%ad|%s" --date=iso
```

**Expected Output:** List of commit hashes, authors, timestamps, and messages for TODAY ONLY.

**Action:** Copy the commit IDs for next steps.

---

## Step 2: Analyze File Changes (Stats Only - No Diffs Yet)

For each commit from Step 1, get a statistical summary to identify which files changed:

### Command to Execute:
```bash
# For jj:
jj diff -r <COMMIT_ID> --stat

# For git:
git diff <COMMIT_ID>^ <COMMIT_ID> --stat
```

**What to Look For:**
- ✅ Source code files: `*.java`, `*.xml`, `*.sh`, `*.md`, `pom.xml`
- ❌ Data files to SKIP: `*.json` (>500 lines), `*.log`, `*backup*`, `verify_results_*.json`

**Action:** Make a list like:
```
Commit abc123:
  - INCLUDE: Distributor.java (150 lines changed)
  - INCLUDE: FpingWorker.java (80 lines changed)
  - SKIP: verify_results_20251007_184831.json (7724 lines - data file)
  - SKIP: verify_result_ForkJoinPool1.json (7634 lines - data file)
```

---

## Step 3: Get Detailed Diffs (Only for Source Files)

**ONLY** request diffs for source code files identified in Step 2. Request ONE FILE AT A TIME.

### Command to Execute:
```bash
# For jj (one file at a time):
jj diff -r <COMMIT_ID> <FILE_PATH>

# Example:
jj diff -r mqltskru src/main/java/com/practice/urlPoller/Distributor.java

# For git:
git diff <COMMIT_ID>^ <COMMIT_ID> -- <FILE_PATH>
```

**Context-Saving Tips:**
- Request diffs for **max 2-3 files per commit** initially
- If a file has >300 lines of changes, just note it as "major refactor" without full diff
- Focus on files mentioned in the commit message

---

## Step 4: Generate Report Structure

Based on the information gathered, create an org-mode report with this structure:

```org
#+TITLE: Daily Progress Report - {DATE}
#+AUTHOR: Prem Modha (Trainee Software Engineer)
#+DATE: {DATE}

* Executive Summary
- Total Commits: {N}
- Source Files Modified: {N}
- Key Achievements: [2-3 bullet points of main accomplishments]
- Time Period: {HH:MM} - {HH:MM}

* Detailed Commit Analysis

** Commit: {SHORT_HASH} - {COMMIT_MESSAGE}
   :PROPERTIES:
   :COMMIT_ID: {FULL_HASH}
   :TIME: {HH:MM:SS}
   :FILES_CHANGED: {N}
   :END:

*** Changes Made
    - *File:* =src/main/java/com/practice/urlPoller/{FileName}.java=
      - Added method: ~methodName()~ - {purpose}
      - Modified logic: {high-level description}
      - Reason: {why this change was needed}
    
    - *File:* =pom.xml=
      - Updated dependency: {name} {version}

*** Impact & Learning
    - {What did this commit achieve?}
    - {What was learned?}
    - {How does it improve the system?}

** Commit: {NEXT_COMMIT}
   [... repeat structure ...]

* Minor Changes & Refactorings
** Configuration Updates
   - {grouped minor config changes}

** Code Cleanup
   - {grouped refactorings}

* Files Excluded from Analysis
  The following files were excluded from detailed analysis due to size/type:
  - =verify_results_20251007_184831.json= (7724 lines - test data)
  - =verify_result_ForkJoinPool1.json= (7634 lines - test data)

* Reflections & Next Steps
** What Went Well
   - {reflection point 1}
   - {reflection point 2}

** Challenges Faced
   - {challenge 1}
   - {how it was resolved}

** Tomorrow's Goals
   - {planned task 1}
   - {planned task 2}
```

---

## Step 5: Content Guidelines

### ✅ INCLUDE in Report:
- New features or feature enhancements
- Bug fixes (with description of the bug)
- Architecture changes (new classes, design pattern changes)
- Performance optimizations
- Algorithm improvements
- Dependency updates (if significant)
- Configuration changes affecting behavior

### ❌ EXCLUDE from Report:
- Pure whitespace/formatting changes
- File renames without logic changes
- Generated files (JSON outputs, compiled artifacts)
- Log file modifications
- Backup file changes
- Test data file changes (>500 lines)
- Binary file changes

### 📝 GROUP UNDER "Minor Changes":
- Variable/method renames
- Code style improvements
- Comment additions
- Small refactorings (<20 lines)
- Import statement organization

---

## Execution Checklist

When using gemini-cli or any LLM, execute in this order:

1. [ ] Run date-based commit log command (Step 1)
2. [ ] For each commit, run `--stat` command (Step 2)
3. [ ] Identify source files vs data files
4. [ ] Request diffs ONLY for source files, ONE AT A TIME (Step 3)
5. [ ] Generate org-mode report with proper structure (Step 4)
6. [ ] Add executive summary and reflections
7. [ ] Save as `progress-report-{YYYY-MM-DD}.org`

---

## Example Workflow

```bash
# 1. Get commits for today
jj log --no-graph --after "2025-10-07T00:00:00" --before "2025-10-07T23:59:59"

# Output shows 4 commits: mqltskru, lsnmyzvm, vvqzqumx, kuozxyos

# 2. Check stats for first commit
jj diff -r mqltskru --stat

# Output shows:
# - verify_result_ForkJoinPool1.json (7634 lines) ← SKIP THIS
# - verify_results_20251007_184831.json (7724 lines) ← SKIP THIS

# 3. Since only data files changed, note: "Commit mqltskru: Test data updates only"

# 4. Check next commit
jj diff -r lsnmyzvm --stat

# Output shows:
# - src/main/java/com/practice/urlPoller/Main.java (10 lines)
# - src/main/java/com/practice/urlPoller/Distributor.java (25 lines)

# 5. Get detailed diff for source files
jj diff -r lsnmyzvm src/main/java/com/practice/urlPoller/Main.java
jj diff -r lsnmyzvm src/main/java/com/practice/urlPoller/Distributor.java

# 6. Analyze changes and add to report
```

---

## Context Management Tips

- **Batch by commit:** Analyze one complete commit before moving to next
- **Skip obvious data files:** Never request diff for *.json, *.log >500 lines
- **Use --stat first:** Always check what changed before getting full diff
- **Summarize large refactors:** If >300 lines changed, just describe high-level intent
- **Focus on "why":** Explain the purpose, not just "what" changed

---

## Output File Naming

Save the generated report as:
```
progress-report-{YYYY-MM-DD}.org
```

Example: `progress-report-2025-10-07.org`
