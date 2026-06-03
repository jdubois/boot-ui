import re
import os
import glob

# Read the original file
core_dir = "bootui-core/src/main/java/io/github/jdubois/bootui/core"
dto_dir = f"{core_dir}/dto"
os.makedirs(dto_dir, exist_ok=True)

with open(f"{core_dir}/BootUiDtos.java", "r") as f:
    content = f.read()

# Extract javadocs and records
records = []
lines = content.split('\n')
in_record = False
record_str = ""
brace_count = 0
name = ""
javadoc = []

i = 0
while i < len(lines):
    line = lines[i]
    if not in_record:
        if line.strip().startswith("/**"):
            # Start of javadoc
            doc_lines = []
            while i < len(lines) and not lines[i].strip().endswith("*/"):
                doc_lines.append(lines[i])
                i += 1
            if i < len(lines):
                doc_lines.append(lines[i])
            javadoc = doc_lines
            i += 1
            continue
        
        m = re.search(r'public record ([A-Za-z0-9]+)', line)
        if m:
            in_record = True
            brace_count = 0
            record_str = ""
            name = m.group(1)
            # Add line to record
    
    if in_record:
        # Check if record is one liner
        # e.g., public record ConfigOverrideRequest(String name, String value, Boolean persist) {}
        record_str += line + "\n"
        brace_count += line.count('{')
        brace_count -= line.count('}')
        if brace_count == 0 and '{' in record_str:
            in_record = False
            # Clean up indentation of record_str (remove 4 spaces)
            clean_record = "\n".join(l[4:] if l.startswith("    ") else l for l in record_str.split("\n"))
            clean_javadoc = "\n".join(l[4:] if l.startswith("    ") else l for l in javadoc)
            records.append((name, clean_javadoc, clean_record))
            javadoc = []
    
    i += 1

# Generate the files
record_names = set(r[0] for r in records)

for name, doc, body in records:
    file_path = f"{dto_dir}/{name}.java"
    # Find imports needed
    imports = set()
    if "List<" in body:
        imports.add("import java.util.List;")
    if "Map<" in body:
        imports.add("import java.util.Map;")
    
    out = []
    out.append("package io.github.jdubois.bootui.core.dto;\n")
    if imports:
        out.append("\n".join(sorted(imports)) + "\n")
    if doc:
        out.append(doc)
    out.append(body.strip() + "\n")
    
    with open(file_path, "w") as f:
        f.write("\n".join(out))

# Now we need to update all other files in the project
java_files = glob.glob("**/*.java", recursive=True)

for file in java_files:
    if "BootUiDtos.java" in file:
        continue
    
    with open(file, "r") as f:
        fc = f.read()
    
    if "BootUiDtos" not in fc:
        continue
    
    # We found BootUiDtos
    # Find all used DTOs in this file
    used_dtos = set()
    for rn in record_names:
        if f"BootUiDtos.{rn}" in fc or f"import io.github.jdubois.bootui.core.BootUiDtos.{rn};" in fc or re.search(r'\b' + rn + r'\b', fc):
            if f"BootUiDtos.{rn}" in fc:
                used_dtos.add(rn)
                
    # Also check if it was imported statically or generally
    for rn in record_names:
        if rn in fc:
            used_dtos.add(rn)
            
    # Replace BootUiDtos.Name with Name
    for rn in used_dtos:
        fc = re.sub(r'BootUiDtos\.' + rn + r'\b', rn, fc)
    
    # Remove old imports
    fc = re.sub(r'import io\.github\.jdubois\.bootui\.core\.BootUiDtos\.[A-Za-z0-9_]+;\n', '', fc)
    fc = re.sub(r'import io\.github\.jdubois\.bootui\.core\.BootUiDtos;\n', '', fc)
    
    # Add new imports
    # Find where to put them - usually after package or other imports
    new_imports = []
    for rn in sorted(used_dtos):
        # check if it actually exists as a word to avoid unused imports
        if re.search(r'\b' + rn + r'\b', fc):
            new_imports.append(f"import io.github.jdubois.bootui.core.dto.{rn};")
    
    if new_imports:
        # Find the last import
        import_idx = fc.rfind("import ")
        if import_idx != -1:
            end_of_line = fc.find("\n", import_idx)
            fc = fc[:end_of_line+1] + "\n".join(new_imports) + "\n" + fc[end_of_line+1:]
        else:
            # After package
            pkg_idx = fc.find("package ")
            if pkg_idx != -1:
                end_of_line = fc.find("\n", pkg_idx)
                fc = fc[:end_of_line+1] + "\n" + "\n".join(new_imports) + "\n" + fc[end_of_line+1:]
                
    # Remove duplicate imports that might have been added
    # We can use a simple trick: split lines, unique them for imports
    final_lines = []
    seen_imports = set()
    for line in fc.split("\n"):
        if line.startswith("import io.github.jdubois.bootui.core.dto."):
            if line in seen_imports:
                continue
            seen_imports.add(line)
        final_lines.append(line)
        
    with open(file, "w") as f:
        f.write("\n".join(final_lines))

