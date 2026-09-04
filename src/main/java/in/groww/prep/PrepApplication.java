package in.groww.prep;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // drives SimulatedMarketDataProvider.tick()
public class PrepApplication {
    public static void main(String[] args) {
        SpringApplication.run(PrepApplication.class, args);
    }
}
