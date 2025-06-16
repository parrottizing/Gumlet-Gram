import re

input_path = "D:\Coding\StudioProjects\Gumlet-Gram\TMessagesProj\src\main\res\values\strings.xml"   # например, 'app/src/main/res/values/strings.xml'
output_path = "D:\Coding\StudioProjects\Gumlet-Gram\TMessagesProj\src\main\res\values\strings_fixed.xml"  # для проверки

def repl(match):
    # Все найдённые %s, %d, %f и т.д. без индекса → %1$s, %2$d, …
    placeholders = re.findall(r'%[sdfoxegc]', match.group(0))
    for i, ph in enumerate(placeholders, 1):
        match.group(0)  # просто для дебага
        match = re.sub(r'%[sdfoxegc]', f'%{i}${ph[1]}', match, count=1)
    return match

with open(input_path, encoding="utf-8") as f:
    content = f.read()

# Регулярка: ищет строки с более чем одним плейсхолдером без индексов
def fix_line(line):
    if re.search(r'%[sdfoxegc].*%[sdfoxegc]', line) and not re.search(r'%\d+\$', line):
        # Исправить каждую такую строку
        def repl_all(m):
            phs = list(re.finditer(r'%[sdfoxegc]', m.group(0)))
            result = m.group(0)
            for idx, ph in enumerate(phs, 1):
                # Заменить только если нет индекса!
                result = result.replace(ph.group(0), f'%{idx}${ph.group(0)[1]}', 1)
            return result
        return re.sub(r'>[^<]*<', lambda m: repl_all(m), line)
    return line

fixed = []
for line in content.splitlines():
    fixed.append(fix_line(line))

with open(output_path, "w", encoding="utf-8") as f:
    f.write('\n'.join(fixed))