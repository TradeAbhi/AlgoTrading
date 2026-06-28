package com.trading.algo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@ComponentScan(basePackages = {"com.trading.algo", "com.stockanalyzer"})

public class AlgoTradingApplication {

	public static void main(String[] args) {
		SpringApplication.run(AlgoTradingApplication.class, args);
	}

}
