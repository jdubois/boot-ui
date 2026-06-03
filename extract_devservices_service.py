import re

with open("/tmp/workspace/jdubois/boot-ui/bootui-autoconfigure/src/main/java/io/github/jdubois/bootui/autoconfigure/web/DevServicesService.java", "r") as f:
    fc = f.read()

# Replace class name
fc = fc.replace("public class DevServicesController implements ApplicationListener<ApplicationEvent> {", "class DevServicesService {")

# Remove @RestController, @ConditionalOnClass, @RequestMapping
fc = re.sub(r'@RestController\n', '', fc)
fc = re.sub(r'@ConditionalOnClass\([^)]+\)\n', '', fc)
fc = re.sub(r'@RequestMapping\("[^"]+"\)\n', '', fc)

# Remove @GetMapping, @PostMapping annotations
fc = re.sub(r'\s*@GetMapping\("[^"]*"\)\n', '\n', fc)
fc = re.sub(r'\s*@GetMapping\n', '\n', fc)
fc = re.sub(r'\s*@PostMapping\("[^"]*"\)\n', '\n', fc)

# Remove @PathVariable annotations
fc = re.sub(r'@PathVariable ', '', fc)

# Remove @Override annotations
fc = re.sub(r'\s*@Override\n\s*public void onApplicationEvent', '\n    public void onApplicationEvent', fc)

# Fix constructor name
fc = fc.replace("public DevServicesController(", "DevServicesService(")

with open("/tmp/workspace/jdubois/boot-ui/bootui-autoconfigure/src/main/java/io/github/jdubois/bootui/autoconfigure/web/DevServicesService.java", "w") as f:
    f.write(fc)

