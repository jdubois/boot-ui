import re
import os
import glob

with open("bootui-core/src/main/java/io/github/jdubois/bootui/core/BootUiDtos.java", "r") as f:
    content = f.read()

# We need to extract all the records.
# A record might span multiple lines, contain generic lists, etc.
# We can find all "public record Name(...) {" or "public record Name(...) {}"
# and extract the body.

# It's better to just regex for "    (?:/\*\*.*?\*/\s*)?public record ([A-Za-z0-9]+)\s*\([^)]*\)\s*(?:\{\s*})?"
# Wait, some records have bodies.
# Let's use a simple brace matching to extract the records.

records = []
current_record = None
brace_count = 0
in_record = False
record_str = ""

lines = content.split('\n')
for line in lines:
    if not in_record:
        m = re.search(r'public record ([A-Za-z0-9]+)', line)
        if m:
            in_record = True
            brace_count = 0
            record_str = ""
            name = m.group(1)
            # Find any javadoc immediately before it?
            # Too complex, skip javadoc for now, or maybe just grab it
    
    if in_record:
        record_str += line + "\n"
        brace_count += line.count('{')
        brace_count -= line.count('}')
        if brace_count == 0 and '{' in record_str:
            in_record = False
            records.append((name, record_str))
        elif brace_count == 0 and line.strip().endswith('{}'):
             # wait, if it's "public record X(...) {}" on one line, brace count goes +1 -1 = 0
             in_record = False
             records.append((name, record_str))

for name, body in records:
    print(name)

