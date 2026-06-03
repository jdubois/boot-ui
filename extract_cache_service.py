import re

with open("/tmp/workspace/jdubois/boot-ui/bootui-autoconfigure/src/main/java/io/github/jdubois/bootui/autoconfigure/web/CacheService.java", "r") as f:
    fc = f.read()

# Replace class name
fc = fc.replace("public class CacheController {", "class CacheService {")

# Remove @RestController, @ConditionalOnClass, @RequestMapping
fc = re.sub(r'@RestController\n', '', fc)
fc = re.sub(r'@ConditionalOnClass\(CacheManager\.class\)\n', '', fc)
fc = re.sub(r'@RequestMapping\("/bootui/api/cache"\)\n', '', fc)

# Remove @GetMapping, @PostMapping annotations
fc = re.sub(r'\s*@GetMapping\n', '\n', fc)
fc = re.sub(r'\s*@PostMapping\("/clear"\)\n', '\n', fc)

# Remove @RequestBody
fc = re.sub(r'@RequestBody\(required = false\) ', '', fc)

# Fix constructor name
fc = fc.replace("public CacheController(", "CacheService(")
fc = fc.replace("public CacheService(", "CacheService(")

# Remove @Autowired
fc = re.sub(r'\s*@Autowired\n\s*CacheService\(', '\n    CacheService(', fc)

with open("/tmp/workspace/jdubois/boot-ui/bootui-autoconfigure/src/main/java/io/github/jdubois/bootui/autoconfigure/web/CacheService.java", "w") as f:
    f.write(fc)

