import re

with open("/tmp/workspace/jdubois/boot-ui/bootui-autoconfigure/src/main/java/io/github/jdubois/bootui/autoconfigure/web/SecurityService.java", "r") as f:
    fc = f.read()

# Replace class name
fc = fc.replace("public class SecurityController {", "class SecurityService {")

# Remove @RestController, @ConditionalOnClass, @RequestMapping
fc = re.sub(r'@RestController\n', '', fc)
fc = re.sub(r'@ConditionalOnClass\(FilterChainProxy\.class\)\n', '', fc)
fc = re.sub(r'@RequestMapping\("/bootui/api/security"\)\n', '', fc)

# Remove @GetMapping annotations
fc = re.sub(r'\s*@GetMapping\("[^"]*"\)\n', '\n', fc)
fc = re.sub(r'\s*@GetMapping\n', '\n', fc)

# Remove @RequestParam annotations
fc = re.sub(r'@RequestParam\(defaultValue = "([^"]*)"\) ', '', fc)

# Fix constructor name
fc = fc.replace("public SecurityController(", "SecurityService(")
fc = fc.replace("public SecurityService(", "SecurityService(")

# Remove @Autowired
fc = re.sub(r'\s*@Autowired\n\s*SecurityService\(', '\n    SecurityService(', fc)

with open("/tmp/workspace/jdubois/boot-ui/bootui-autoconfigure/src/main/java/io/github/jdubois/bootui/autoconfigure/web/SecurityService.java", "w") as f:
    f.write(fc)

