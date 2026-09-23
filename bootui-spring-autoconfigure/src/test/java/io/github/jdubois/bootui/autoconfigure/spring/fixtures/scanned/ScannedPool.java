package app.advisoraudit.scanned;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component("scannedPool")
public class ScannedPool extends ThreadPoolTaskExecutor {}
