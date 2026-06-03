import re
import os
import glob

dto_dir = "bootui-core/src/main/java/io/github/jdubois/bootui/core/dto"
dtos = set([f.replace(".java", "") for f in os.listdir(dto_dir) if f.endswith(".java")])

java_files = glob.glob("**/*.java", recursive=True)

for file in java_files:
    if "BootUiDtos.java" in file or file.startswith("bootui-core/src/main/java/io/github/jdubois/bootui/core/dto/"):
        continue
        
    with open(file, "r") as f:
        fc = f.read()
        
    original_fc = fc
    
    # Set of dtos to import
    dtos_to_import = set()
    
    # 1. Find explicit inner imports
    matches = re.findall(r'import io\.github\.jdubois\.bootui\.core\.BootUiDtos\.([A-Za-z0-9_]+);', fc)
    for m in matches:
        dtos_to_import.add(m)
        
    # Replace those imports
    fc = re.sub(r'import io\.github\.jdubois\.bootui\.core\.BootUiDtos\.([A-Za-z0-9_]+);', r'import io.github.jdubois.bootui.core.dto.\1;', fc)
    
    # Remove wildcard or basic class import
    fc = re.sub(r'import io\.github\.jdubois\.bootui\.core\.BootUiDtos;\n?', '', fc)
    
    # 2. Find usage of BootUiDtos.ClassName
    matches = re.findall(r'BootUiDtos\.([A-Za-z0-9_]+)', fc)
    for m in matches:
        dtos_to_import.add(m)
        
    # Replace usages
    fc = re.sub(r'BootUiDtos\.([A-Za-z0-9_]+)', r'\1', fc)
    
    # 3. Add imports if needed
    new_imports = []
    for dto in sorted(dtos_to_import):
        imp = f"import io.github.jdubois.bootui.core.dto.{dto};"
        if imp not in fc:
            new_imports.append(imp)
            
    if new_imports:
        # insert after package
        lines = fc.split("\n")
        out_lines = []
        inserted = False
        
        # let's try to find where other imports are
        last_import = -1
        for i, line in enumerate(lines):
            if line.startswith("import "):
                last_import = i
                
        if last_import != -1:
            out_lines = lines[:last_import+1] + new_imports + lines[last_import+1:]
        else:
            for i, line in enumerate(lines):
                out_lines.append(line)
                if line.startswith("package ") and not inserted:
                    out_lines.append("")
                    out_lines.extend(new_imports)
                    inserted = True
        fc = "\n".join(out_lines)
        
    if fc != original_fc:
        with open(file, "w") as f:
            f.write(fc)

