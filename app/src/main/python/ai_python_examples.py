"""
AI Python Code Examples for RikkaHub Sandbox
============================================
This file contains common code patterns that AI can reference when using python_exec.
These are examples only - not executed directly.

Usage in python_exec operation:
  Params: {"code": "paste example code here"}
"""

# =============================================================================
# 1. EXCEL / CSV OPERATIONS (pandas, openpyxl)
# =============================================================================

# --- Read Excel and process ---
EXCEL_READ_TEMPLATE = """
# Read Excel file
import pandas as pd

df = pd.read_excel('data.xlsx')
# or: df = pd.read_csv('data.csv')

# Display basic info
print(f"Rows: {len(df)}, Columns: {len(df.columns)}")
print(f"Columns: {list(df.columns)}")
print(df.head(10))  # First 10 rows

# Filter data
filtered = df[df['column_name'] > 100]

# Sort
sorted_df = df.sort_values('column_name', ascending=False)

# Group and aggregate
summary = df.groupby('category').agg({'value': ['sum', 'mean', 'count']})

# Return result for AI
result = {
    'row_count': len(df),
    'columns': list(df.columns),
    'summary': summary.to_dict()
}
"""

# --- Write to Excel ---
EXCEL_WRITE_TEMPLATE = """
import pandas as pd

# Create DataFrame from data
data = {
    'Name': ['Alice', 'Bob', 'Charlie'],
    'Age': [25, 30, 35],
    'City': ['NYC', 'LA', 'Chicago']
}
df = pd.DataFrame(data)

# Write to Excel with formatting
df.to_excel('output.xlsx', index=False, sheet_name='Sheet1')

# Write multiple sheets
with pd.ExcelWriter('output.xlsx') as writer:
    df.to_excel(writer, sheet_name='Data', index=False)
    summary.to_excel(writer, sheet_name='Summary')

print("Excel file created successfully")
"""

# --- CSV processing ---
CSV_TEMPLATE = """
import csv
import pandas as pd

# Read CSV
df = pd.read_csv('data.csv', encoding='utf-8')

# Or use csv module for more control
with open('data.csv', 'r', encoding='utf-8') as f:
    reader = csv.DictReader(f)
    rows = list(reader)

# Process and write back
df_cleaned = df.dropna()  # Remove empty rows
df_cleaned.to_csv('cleaned.csv', index=False)

result = {'processed_rows': len(df_cleaned)}
"""


# =============================================================================
# 2. PDF OPERATIONS (PyPDF2)
# =============================================================================

# --- Extract text from PDF ---
PDF_EXTRACT_TEMPLATE = """
from PyPDF2 import PdfReader

reader = PdfReader('document.pdf')
num_pages = len(reader.pages)
print(f"Total pages: {num_pages}")

# Extract text from specific pages
for i, page in enumerate(reader.pages[:5]):  # First 5 pages
    text = page.extract_text()
    print(f"\\n--- Page {i+1} ---")
    print(text[:500])  # First 500 chars

# Extract all text
all_text = "\\n".join(page.extract_text() or "" for page in reader.pages)

# Write to file
with open('output.txt', 'w', encoding='utf-8') as f:
    f.write(all_text)

result = {'total_pages': num_pages, 'text_length': len(all_text)}
"""

# --- Merge PDFs ---
PDF_MERGE_TEMPLATE = """
from PyPDF2 import PdfWriter, PdfReader
import os

writer = PdfWriter()

# Add pages from multiple PDFs
for filename in ['file1.pdf', 'file2.pdf', 'file3.pdf']:
    if os.path.exists(filename):
        reader = PdfReader(filename)
        for page in reader.pages:
            writer.add_page(page)
        print(f"Added {len(reader.pages)} pages from {filename}")

# Save merged PDF
with open('merged.pdf', 'wb') as f:
    writer.write(f)

print("PDFs merged successfully")
"""

# --- Split PDF ---
PDF_SPLIT_TEMPLATE = """
from PyPDF2 import PdfWriter, PdfReader

reader = PdfReader('document.pdf')

# Split into individual pages
for i, page in enumerate(reader.pages):
    writer = PdfWriter()
    writer.add_page(page)
    with open(f'page_{i+1}.pdf', 'wb') as f:
        writer.write(f)

print(f"Split into {len(reader.pages)} separate PDFs")
"""


# =============================================================================
# 3. IMAGE PROCESSING (Pillow)
# =============================================================================

# --- Image resize and compress ---
IMAGE_RESIZE_TEMPLATE = """
from PIL import Image
import os

# Open image
img = Image.open('photo.jpg')
print(f"Original size: {img.size}, Mode: {img.mode}")

# Resize maintaining aspect ratio
max_size = (800, 800)
img.thumbnail(max_size, Image.Resampling.LANCZOS)

# Save with compression
img.save('photo_thumb.jpg', 'JPEG', quality=85, optimize=True)

# Create different sizes
sizes = {
    'small': (150, 150),
    'medium': (400, 400),
    'large': (800, 800)
}

for name, size in sizes.items():
    img_copy = Image.open('photo.jpg')
    img_copy.thumbnail(size, Image.Resampling.LANCZOS)
    img_copy.save(f'photo_{name}.jpg', 'JPEG', quality=85)
    print(f"Created {name}: {img_copy.size}")

result = {'original_size': Image.open('photo.jpg').size, 'sizes_created': list(sizes.keys())}
"""

# --- Image format conversion ---
IMAGE_CONVERT_TEMPLATE = """
from PIL import Image
import os

# Convert to different formats
formats = ['PNG', 'WEBP', 'JPEG']
img = Image.open('image.png')

for fmt in formats:
    ext = fmt.lower()
    if img.mode in ('RGBA', 'P') and fmt == 'JPEG':
        # Convert RGBA to RGB for JPEG
        img_rgb = img.convert('RGB')
        img_rgb.save(f'converted.{ext}', fmt)
    else:
        img.save(f'converted.{ext}', fmt)
    size = os.path.getsize(f'converted.{ext}')
    print(f"{fmt}: {size/1024:.1f} KB")
"""

# --- Batch image processing ---
IMAGE_BATCH_TEMPLATE = """
from PIL import Image, ImageOps
import os

# Process all images in directory
for filename in os.listdir('.'):
    if filename.lower().endswith(('.jpg', '.jpeg', '.png')):
        try:
            img = Image.open(filename)
            
            # Apply operations
            img = ImageOps.grayscale(img)  # Convert to grayscale
            img = img.resize((200, 200))   # Resize
            
            # Save with prefix
            name, ext = os.path.splitext(filename)
            img.save(f'processed_{name}.jpg', 'JPEG', quality=80)
            print(f"Processed: {filename}")
        except Exception as e:
            print(f"Error processing {filename}: {e}")
"""


# =============================================================================
# 4. WEB SCRAPING (requests + BeautifulSoup)
# =============================================================================

# --- Basic web scraping ---
WEB_SCRAPE_TEMPLATE = """
import requests
from bs4 import BeautifulSoup

# Fetch webpage
url = 'https://example.com'
headers = {'User-Agent': 'Mozilla/5.0 (compatible; Bot/1.0)'}
response = requests.get(url, headers=headers, timeout=30)
response.raise_for_status()

# Parse HTML
soup = BeautifulSoup(response.text, 'html.parser')

# Extract links
links = []
for a in soup.find_all('a', href=True)[:20]:  # First 20 links
    links.append({'text': a.get_text(strip=True), 'url': a['href']})

# Extract text content
text = soup.get_text(separator='\\n', strip=True)

# Save
with open('scraped.txt', 'w', encoding='utf-8') as f:
    f.write(text)

result = {'title': soup.title.string if soup.title else None, 'links_count': len(links)}
"""

# --- API request and JSON processing ---
API_REQUEST_TEMPLATE = """
import requests
import json

# Make API request
url = 'https://api.example.com/data'
headers = {'Accept': 'application/json'}
params = {'page': 1, 'limit': 10}

response = requests.get(url, headers=headers, params=params, timeout=30)
data = response.json()

# Process data
items = data.get('items', [])
print(f"Retrieved {len(items)} items")

# Save to JSON file
with open('api_response.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, indent=2, ensure_ascii=False)

# Convert to CSV
import pandas as pd
df = pd.DataFrame(items)
df.to_csv('api_data.csv', index=False)

result = {'item_count': len(items), 'columns': list(df.columns) if items else []}
"""

# --- Download file ---
DOWNLOAD_TEMPLATE = """
import requests
import os

url = 'https://example.com/file.pdf'
save_path = 'downloads/file.pdf'

# Ensure directory exists
os.makedirs(os.path.dirname(save_path), exist_ok=True)

# Download with progress
response = requests.get(url, stream=True, timeout=60)
response.raise_for_status()

total_size = int(response.headers.get('content-length', 0))
downloaded = 0

with open(save_path, 'wb') as f:
    for chunk in response.iter_content(chunk_size=8192):
        if chunk:
            f.write(chunk)
            downloaded += len(chunk)
            if total_size > 0:
                percent = (downloaded / total_size) * 100
                print(f"\\rDownloaded: {percent:.1f}%", end='')

print(f"\\nSaved to: {save_path}")
result = {'file_size': downloaded}
"""


# =============================================================================
# 5. DATA PROCESSING & CONVERSION
# =============================================================================

# --- JSON to CSV conversion ---
JSON_CSV_TEMPLATE = """
import json
import pandas as pd

# Read JSON
with open('data.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

# Convert to DataFrame (handles nested data)
if isinstance(data, list):
    df = pd.json_normalize(data)
else:
    df = pd.json_normalize([data])

# Save to CSV
df.to_csv('output.csv', index=False)
print(f"Converted: {len(df)} rows, {len(df.columns)} columns")
print(f"Columns: {list(df.columns)}")
"""

# --- YAML/TOML processing ---
CONFIG_TEMPLATE = """
import yaml
import toml
import json

# Read YAML
with open('config.yaml', 'r', encoding='utf-8') as f:
    config = yaml.safe_load(f)

# Modify
config['version'] = '2.0'
config['settings']['debug'] = False

# Save as TOML
with open('config.toml', 'w', encoding='utf-8') as f:
    toml.dump(config, f)

# Save as JSON
with open('config.json', 'w', encoding='utf-8') as f:
    json.dump(config, f, indent=2)

print("Config converted to multiple formats")
"""


# =============================================================================
# 6. TEXT PROCESSING
# =============================================================================

# --- Text analysis ---
TEXT_ANALYSIS_TEMPLATE = """
import re
from collections import Counter

# Read text
with open('document.txt', 'r', encoding='utf-8') as f:
    text = f.read()

# Statistics
words = re.findall(r'\\b\\w+\\b', text.lower())
word_count = len(words)
unique_words = len(set(words))
char_count = len(text)

# Most common words
common = Counter(words).most_common(20)

# Find patterns
emails = re.findall(r'\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b', text)
urls = re.findall(r'https?://\\S+', text)

# Save analysis
with open('analysis.txt', 'w', encoding='utf-8') as f:
    f.write(f"Word count: {word_count}\\n")
    f.write(f"Unique words: {unique_words}\\n")
    f.write(f"Most common: {common}\\n")

result = {
    'word_count': word_count,
    'unique_words': unique_words,
    'top_words': common[:5],
    'emails_found': len(emails)
}
"""


# =============================================================================
# 7. CODE QUALITY TOOLS
# =============================================================================

# --- Format code with black ---
CODE_FORMAT_TEMPLATE = """
import subprocess
import os

# Format Python file with black
result = subprocess.run(
    ['python', '-m', 'black', 'script.py', '--quiet'],
    capture_output=True,
    text=True
)

if result.returncode == 0:
    print("Code formatted successfully")
else:
    print(f"Formatting error: {result.stderr}")
"""

# --- Lint with pylint ---
CODE_LINT_TEMPLATE = """
import subprocess

# Run pylint
result = subprocess.run(
    ['python', '-m', 'pylint', 'script.py', '--output-format=json'],
    capture_output=True,
    text=True
)

print(result.stdout)
"""


# =============================================================================
# 10. SHELL SCRIPT EXECUTION (exec_script operation)
# =============================================================================

# Note: These are examples for the exec_script convenience operation
# Use exec_script to reduce token usage by combining multiple commands

SHELL_BATCH_RENAME = """
# Use with exec_script operation:
# {"operation": "exec_script", "params": {"script": "...this script..."}}

# Batch rename files
for file in *.txt; do
    [ -f "$file" ] || continue
    newname="backup_${file}"
    mv "$file" "$newname"
    echo "Renamed: $file -> $newname"
done
"""

SHELL_TEXT_PROCESSING = """
# Chain multiple text processing commands
# Count ERROR lines per hour in log file
grep "ERROR" app.log | 
    cut -d' ' -f2 | 
    cut -d':' -f1 | 
    sort | 
    uniq -c | 
    sort -rn > error_stats.txt

echo "Top error hours:"
head -5 error_stats.txt
"""

SHELL_FILE_ANALYSIS = """
# Analyze directory contents
echo "=== Directory Analysis ==="
echo "Total files:"
find . -type f | wc -l

echo ""
echo "File types:"
find . -type f | 
    grep -o '\.[^.]*$' | 
    sort | 
    uniq -c | 
    sort -rn | 
    head -10

echo ""
echo "Largest files:"
ls -lhS | head -10
"""


# =============================================================================
# 11. MULTI-LANGUAGE EXECUTION
# =============================================================================

# --- JavaScript Code Examples ---
JS_DATA_PROCESSING = """
// Use with exec_js operation:
// {"operation": "exec_js", "params": {"code": "...this code..."}}

// Process JSON data
const data = {users: [{name: "Alice", age: 25}, {name: "Bob", age: 30}]};
const adults = data.users.filter(u => u.age >= 18);
const names = adults.map(u => u.name).join(", ");
console.log(names);  // Output: Alice, Bob

// String manipulation
const text = "hello world";
const capitalized = text.split(' ').map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
console.log(capitalized);  // Output: Hello World
"""

JS_ALGORITHMS = """
// Algorithm validation in JS
function fibonacci(n) {
    if (n <= 1) return n;
    let a = 0, b = 1;
    for (let i = 2; i <= n; i++) {
        [a, b] = [b, a + b];
    }
    return b;
}

// Test
const results = [];
for (let i = 0; i < 10; i++) {
    results.push(fibonacci(i));
}
console.log(results);  // [0, 1, 1, 2, 3, 5, 8, 13, 21, 34]
"""

# --- Lua Code Examples ---
LUA_FILE_PROCESSING = """
-- Use with exec_lua operation:
-- {"operation": "exec_lua", "params": {"code": "...this code..."}}

-- Read and process file
local content = read_file("data.txt")
local lines = {}
for line in content:gmatch("[^\r\n]+") do
    table.insert(lines, line)
end

-- Process each line
local count = 0
for i, line in ipairs(lines) do
    if line:match("ERROR") then
        count = count + 1
        print("Error at line " .. i .. ": " .. line)
    end
end

print("Total errors: " .. count)
"""

LUA_DATA_TRANSFORMATION = """
-- Transform data structure
local users = {
    {name = "Alice", age = 25, city = "NYC"},
    {name = "Bob", age = 30, city = "LA"},
    {name = "Charlie", age = 35, city = "Chicago"}
}

-- Group by city
local by_city = {}
for _, user in ipairs(users) do
    local city = user.city
    if not by_city[city] then
        by_city[city] = {}
    end
    table.insert(by_city[city], user.name)
end

-- Output result
for city, names in pairs(by_city) do
    print(city .. ": " .. table.concat(names, ", "))
end
"""

# --- Code Analysis Examples ---
CODE_ANALYSIS_PYTHON = """
# Use with analyze_code operation:
# {"operation": "analyze_code", "params": {"file_path": "script.py", "language": "python", "operation": "complexity"}}

# Example: Check code complexity
# The operation returns:
# - syntax_valid: true/false
# - metrics: {functions, classes, imports, complexity_score}
# - issues: list of problems

# For AI self-development workflow:
# 1. Read existing code
# 2. Analyze with analyze_code
# 3. Generate modifications
# 4. Write back with write_file
# 5. Re-analyze to verify
"""

CODE_ANALYSIS_KOTLIN = """
# Kotlin analysis (basic syntax check)
# {"operation": "analyze_code", "params": {"file_path": "Feature.kt", "language": "kotlin"}}

# Returns:
# - syntax_valid: true/false (basic checks)
# - issues: brace/parenthesis mismatch warnings
# - detected_keywords: [fun, class, val, var, ...]
# - note: indicates full compilation requires kotlinc
"""


# =============================================================================
# 12. COMPILE CHECK (Built-in Validation)
# =============================================================================

COMPILE_CHECK_PYTHON = """
# Use with compile_check operation:
# {"operation": "compile_check", "params": {"file_path": "script.py", "language": "python"}}

# Validates:
# - Syntax (AST parsing)
# - Linting (ruff/pylint)
# - Formatting (black)

# Example result:
# {
#   "valid": true/false,
#   "issues": [
#     {"line": 10, "column": 5, "level": "error", "message": "SyntaxError: invalid syntax", "tool": "ast"},
#     {"line": 25, "column": 0, "level": "warning", "message": "Line too long", "tool": "ruff"}
#   ],
#   "tool_used": "ruff",
#   "error_count": 1,
#   "warning_count": 1
# }

# Auto-fix formatting issues:
# {"operation": "compile_check", "params": {"file_path": "script.py", "language": "python", "fix": true}}
# This will run black formatter and return the fixed content in "fixed_content"
"""

COMPILE_CHECK_KOTLIN_JAVA = """
# Validate Kotlin/Java syntax without full compilation:
# {"operation": "compile_check", "params": {"file_path": "Main.kt", "language": "kotlin"}}

# Checks:
# - Brace/parenthesis matching
# - Basic structure validation
# - Indentation issues

# Note: This is lightweight validation only.
# Full type checking and compilation requires kotlinc/javac (not available in sandbox)
"""

COMPILE_CHECK_SHELL = """
# Validate shell scripts:
# {"operation": "compile_check", "params": {"file_path": "script.sh", "language": "shell"}}

# Uses sh -n for syntax checking + heuristic analysis
# Detects common issues like:
# - Unclosed quotes
# - Missing 'then' in if statements
# - Brace mismatches
"""

COMPILE_CHECK_DATA_FORMATS = """
# Validate JSON/YAML/TOML:

# JSON
{"operation": "compile_check", "params": {"file_path": "config.json", "language": "json"}}

# YAML
{"operation": "compile_check", "params": {"file_path": "config.yaml", "language": "yaml"}}

# TOML
{"operation": "compile_check", "params": {"file_path": "config.toml", "language": "toml"}}

# All return detailed parse errors with line numbers if invalid
"""

COMPILE_CHECK_IN_WORKFLOW = """
# AI Self-Development with Compile Check:
# ========================================

# 1. Generate new code
write_file('new_feature.py', generated_code)

# 2. Validate syntax
{"operation": "compile_check", "params": {"file_path": "new_feature.py", "language": "python"}}

# 3. If valid, run more checks
{"operation": "compile_check", "params": {"file_path": "new_feature.py", "language": "python", "check_type": "all"}}

# 4. Auto-fix style issues if any
{"operation": "compile_check", "params": {"file_path": "new_feature.py", "language": "python", "fix": true}}

# 5. Only apply if all checks pass
move_file('new_feature.py', 'sandbox_tool.py')
"""

# =============================================================================
# 13. KTLINT - Enhanced Kotlin Analysis (LOCAL, NO REMOTE)
# =============================================================================

KTLINT_INSTALL = """
# Install ktlint for deep Kotlin analysis (~10MB, one-time download):
{"operation": "install_tool", "params": {"tool": "ktlint"}}

# Or specify version:
{"operation": "install_tool", "params": {"tool": "ktlint", "version": "1.2.1"}}

# Installation result:
# {
#   "installed": true,
#   "path": "/path/to/ktlint-1.2.1",
#   "size_mb": 10.5,
#   "cached": false  # true if already installed
# }

# ktlint is saved in sandbox .tools/ directory and persists across sessions
"""

KTLINT_USAGE = """
# Once ktlint is installed, compile_check automatically uses it for Kotlin files:

# Deep analysis with ktlint:
{"operation": "compile_check", "params": {"file_path": "MainActivity.kt", "language": "kotlin"}}

# Returns detailed issues:
# {
#   "valid": false,
#   "issues": [
#     {"line": 45, "column": 12, "level": "error", "message": "Unexpected indentation", "rule": "indent", "tool": "ktlint"},
#     {"line": 30, "level": "warning", "message": "Unused import", "rule": "no-unused-imports", "tool": "ktlint"},
#     {"line": 50, "level": "warning", "message": "Function name should start with lowercase", "rule": "function-naming", "tool": "ktlint"}
#   ],
#   "tool_used": "ktlint",
#   "ktlint_version": "1.2.1"
# }

# Auto-format with ktlint:
{"operation": "compile_check", "params": {"file_path": "MainActivity.kt", "language": "kotlin", "fix": true}}
# This runs ktlint --format and returns the fixed content
"""

KTLINT_EDITORCONFIG = """
# Create .editorconfig in sandbox root to customize ktlint behavior:

write_file('.editorconfig', '''
root = true

[*.{kt,kts}]
indent_style = space
indent_size = 4
max_line_length = 120

# Android Compose specific:
ktlint_function_naming_ignore_when_annotated_with=Composable
''')

# ktlint will automatically pick up these settings
"""

KTLINT_WORKFLOW = """
# Complete AI Kotlin Development Workflow with ktlint:
# =====================================================

# Step 1: Ensure ktlint is installed (one-time)
{"operation": "install_tool", "params": {"tool": "ktlint"}}

# Step 2: AI generates/modifies Kotlin code
# (in python_exec, modify the .kt file)

# Step 3: Deep validation with ktlint
{"operation": "compile_check", "params": {"file_path": "Feature.kt", "language": "kotlin"}}

# Step 4: If issues found, auto-fix formatting
{"operation": "compile_check", "params": {"file_path": "Feature.kt", "language": "kotlin", "fix": true}}

# Step 5: Check again for remaining issues (non-auto-fixable)
{"operation": "compile_check", "params": {"file_path": "Feature.kt", "language": "kotlin"}}

# Step 6: AI fixes remaining issues manually based on error messages
# (naming conventions, unused imports, etc.)

# Step 7: Final validation
{"operation": "compile_check", "params": {"file_path": "Feature.kt", "language": "kotlin"}}

# If valid=true, the code is ready for PC compilation
"""


# =============================================================================
# AI SELF-DEVELOPMENT WORKFLOW (RikkaHub Modding)
# =============================================================================

AI_DEVELOPMENT_WORKFLOW = """
RikkaHub Self-Development Workflow:
====================================

This demonstrates how AI can modify RikkaHub's own Python code in sandbox:

1. READ EXISTING CODE
   {"operation": "read", "params": {"file_path": "sandbox_tool.py"}}

2. ANALYZE CODE STRUCTURE
   {"operation": "analyze_code", "params": {"file_path": "sandbox_tool.py", "language": "python", "operation": "ast"}}

3. GENERATE MODIFICATIONS (in python_exec)
   code = read_file('sandbox_tool.py')
   # ... AI generates new function ...
   new_code = code + "\\n\\ndef new_feature():\\n    pass\\n"
   write_file('sandbox_tool_v2.py', new_code)
   result = "Modifications generated"

4. SYNTAX VALIDATION
   {"operation": "analyze_code", "params": {"file_path": "sandbox_tool_v2.py", "language": "python"}}

5. DIFF GENERATION
   {"operation": "python_exec", "params": {"code": "
import difflib
old = read_file('sandbox_tool.py')
new = read_file('sandbox_tool_v2.py')
diff = difflib.unified_diff(old.splitlines(), new.splitlines(), lineterm='')
write_file('changes.patch', '\\n'.join(diff))
"}}

6. APPLY CHANGES (when satisfied)
   {"operation": "move", "params": {"src": "sandbox_tool_v2.py", "dst": "sandbox_tool.py"}}

Note: Python changes take effect immediately (no APK rebuild needed).
Kotlin changes would need compilation (not available in sandbox).
"""


# =============================================================================
# QUICK REFERENCE
# =============================================================================
QUICK_REFERENCE = """
Available Pre-imported Modules:
- np, numpy: NumPy for numerical computing
- pd, pandas: Pandas for data analysis
- Image: PIL Image for image processing
- requests: HTTP requests
- BeautifulSoup: HTML parsing
- PdfReader, PdfWriter: PDF operations
- yaml, toml: Config file formats
- json, csv, re: Standard data formats
- datetime, hashlib, base64: Utilities

Helper Functions:
- read_file(path): Read text file
- write_file(path, content): Write text file  
- list_files(path='.'): List directory
- download(url, save_path=None): Download file

Tips:
1. Use 'result' variable to return data to AI
2. Print statements are captured in stdout
3. Working directory is set to sandbox root
4. All file paths are relative to sandbox
"""



# =============================================================================
# 8. SQLITE DATABASE OPERATIONS
# =============================================================================

# --- Create and query SQLite database ---
SQLITE_CREATE_TEMPLATE = """
import sqlite3

# Create database and table
conn = sqlite3.connect('mydata.db')
cursor = conn.cursor()

cursor.execute('''
    CREATE TABLE IF NOT EXISTS users (
        id INTEGER PRIMARY KEY,
        name TEXT NOT NULL,
        email TEXT,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    )
''')

# Insert data
users = [
    ('Alice', 'alice@example.com'),
    ('Bob', 'bob@example.com'),
    ('Charlie', 'charlie@example.com')
]
cursor.executemany('INSERT INTO users (name, email) VALUES (?, ?)', users)
conn.commit()

# Query
cursor.execute('SELECT * FROM users')
rows = cursor.fetchall()
for row in rows:
    print(row)

conn.close()
result = {'inserted': len(users)}
"""

# --- Query with pandas ---
SQLITE_PANDAS_TEMPLATE = """
import pandas as pd
import sqlite3

conn = sqlite3.connect('data.db')

# Read into DataFrame
df = pd.read_sql_query('SELECT * FROM sales WHERE amount > 1000', conn)

# Analyze
summary = df.groupby('category')['amount'].agg(['sum', 'mean', 'count'])
print(summary)

# Write back to new table
df.to_sql('high_value_sales', conn, if_exists='replace', index=False)

conn.close()
result = summary.to_dict()
"""


# =============================================================================
# 9. GIT OPERATIONS (Basic Version Control)
# =============================================================================

# --- Initialize and check status ---
GIT_BASIC_TEMPLATE = """
# Note: Git operations available through convenience operations
# Or use dulwich directly for advanced operations

from dulwich.repo import Repo
from dulwich.porcelain import status, add, commit

# Initialize repo (if not exists)
# Repo.init('.')

repo = Repo('.')

# Check status
repo_status = status(repo)
print(f"Staged: {len(repo_status.staged['add'])}")
print(f"Unstaged: {len(repo_status.unstaged)}")
print(f"Untracked: {len(repo_status.untracked)}")

# Add files
# add(repo, ['file.txt'])

# Commit
# commit(repo, b'Initial commit', author=b'User <user@example.com>')
"""


# =============================================================================
# QUICK REFERENCE (Updated)
# =============================================================================
QUICK_REFERENCE = """
Available Pre-imported Modules:
- np, numpy: NumPy for numerical computing
- pd, pandas: Pandas for data analysis
- Image: PIL Image for image processing
- requests: HTTP requests
- BeautifulSoup: HTML parsing
- PdfReader, PdfWriter: PDF operations
- yaml, toml: Config file formats
- json, csv, re: Standard data formats
- datetime, hashlib, base64: Utilities
- sqlite3: SQLite database (Python standard library)

Helper Functions:
- read_file(path): Read text file
- write_file(path, content): Write text file  
- list_files(path='.'): List directory
- download(url, save_path=None): Download file

Multi-Language Support:
- exec_js: Execute JavaScript code
  Example: {"code": "[1,2,3].map(x => x*2)"}
- exec_lua: Execute Lua script  
  Example: {"code": "for i=1,10 do print(i) end"}
- analyze_code: Syntax check and analysis (Python/Kotlin/JS/Lua)
  Example: {"file_path": "script.py", "operation": "complexity"}

Compile Check (Built-in Validation - No External Compiler):
- compile_check: Syntax validation, linting, auto-fix for multiple languages
  Languages: python, kotlin, java, shell, json, yaml, toml, markdown
  Example: {"file_path": "script.py", "language": "python", "check_type": "all"}
  Auto-fix: {"file_path": "script.py", "language": "python", "fix": true}

Tool Installation (Local, No Remote):
- install_tool: Download dev tools to sandbox (ktlint for Kotlin, ~10MB)
  Example: {"tool": "ktlint"} or {"tool": "ktlint", "version": "1.2.1"}
  Once installed, compile_check automatically uses ktlint for deep Kotlin analysis

Convenience Operations (no code needed):
- exec_script: Execute multi-line shell script (REDUCES TOKEN USAGE!)
  Example: {"script": "for f in *.txt; do echo $f; done"}
- process_image: Resize, compress, convert images
- convert_excel: Excel to CSV/JSON
- extract_pdf_text: Extract text from PDF
- download_file: HTTP download
- sqlite_query: Execute SQL on SQLite
- sqlite_tables: Get database schema
- git_init, git_status, git_log, git_diff: Basic Git operations

Tips:
1. Use 'result' variable to return data to AI
2. Print statements are captured in stdout
3. Working directory is set to sandbox root
4. All file paths are relative to sandbox
"""
