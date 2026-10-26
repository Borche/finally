package com.finallyapp.market.config;

import com.finallyapp.market.DefaultMarketDataService;
import com.finallyapp.market.MarketDataService;
import com.finallyapp.market.massive.MassiveClient;
import com.finallyapp.market.massive.MassiveMarketDataSource;
import com.finallyapp.market.simulator.SimulatedMarketDataSource;
import com.finallyapp.market.spi.MarketDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(MarketProperties.class)
public class MarketDataConfig {

    private static final Logger log = LoggerFactory.getLogger(MarketDataConfig.class);

    @Bean(destroyMethod = "close")
    MarketDataSource marketDataSource(MarketProperties props, RestClient.Builder restClientBuilder) {
        if (props.massive().enabled()) {
            log.info("MASSIVE_API_KEY set: using Massive market data ({})", props.massive().baseUrl());
            var client = new MassiveClient(restClientBuilder, props.massive().baseUrl(), props.massive().apiKey());
            return new MassiveMarketDataSource(client, props.massive());
        }
        log.info("MASSIVE_API_KEY not set: using simulated market data");
        return new SimulatedMarketDataSource(props.simulator());
    }

    @Bean
    MarketDataService marketDataService(MarketDataSource source, MarketProperties props) {
        return new DefaultMarketDataService(source, props.defaultTickers());
    }
}
