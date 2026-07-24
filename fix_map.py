import re
import subprocess
import os

def fix_map_errors():
    print("Running maven to get errors...")
    result = subprocess.run(["mvn", "clean", "compile"], capture_output=True, text=True, cwd=r"c:\Users\Sindhu.Yanala\expense-management-service\expense-management-service")
    
    error_pattern = re.compile(r'\[ERROR\] \/?([A-Za-z]:[\\/].+?\.java):\[(\d+),\d+\] cannot find symbol')
    symbol_pattern = re.compile(r'\[ERROR\]\s+symbol:\s+method map\(')
    
    lines = result.stdout.split('\n')
    
    fixes = {}
    
    for i, line in enumerate(lines):
        match = error_pattern.search(line)
        if match:
            # Check if the next line or two contains "method map" and "location: interface java.util.List"
            is_map_error = False
            for j in range(1, 4):
                if i + j < len(lines):
                    if 'method map(' in lines[i+j] and 'interface java.util.List' in lines[i+j+1]:
                        is_map_error = True
                        break
                    elif 'method map(' in lines[i+j]:
                        is_map_error = True
                        break
            
            if is_map_error:
                file_path = match.group(1).replace('/', '\\')
                if file_path.startswith('\\'):
                    file_path = file_path[1:]
                line_num = int(match.group(2))
                if file_path not in fixes:
                    fixes[file_path] = []
                fixes[file_path].append(line_num)
    
    for file_path, line_nums in fixes.items():
        if os.path.exists(file_path):
            with open(file_path, 'r', encoding='utf-8') as f:
                content = f.read().split('\n')
            
            modified = False
            for line_num in line_nums:
                idx = line_num - 1
                if 0 <= idx < len(content):
                    # Replace .map( with .stream().map(
                    old_line = content[idx]
                    if '.map(' in old_line:
                        # Find the matching closing parenthesis for .map(...)
                        # This is a bit tricky if there are multiple parens, but usually it's like .map(mapper::toDto);
                        # Let's do a simple regex: replace `.map(.*)` with `.stream().map(.*).toList()` if it ends with ;
                        new_line = re.sub(r'\.map\(([^)]+)\)\s*;', r'.stream().map(\1).toList();', old_line)
                        if new_line == old_line:
                            # Try just replacing .map( with .stream().map( and appending .toList() at the end
                            if old_line.endswith(';'):
                                new_line = old_line[:-1].replace('.map(', '.stream().map(') + '.toList();'
                            else:
                                new_line = old_line.replace('.map(', '.stream().map(') + '.toList()'
                        
                        content[idx] = new_line
                        modified = True
                        print(f"Fixed {file_path}:{line_num}")
            
            if modified:
                with open(file_path, 'w', encoding='utf-8') as f:
                    f.write('\n'.join(content))
        else:
            print(f"File not found: {file_path}")

if __name__ == "__main__":
    fix_map_errors()
