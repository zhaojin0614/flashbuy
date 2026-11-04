package com.flashbuy;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@MapperScan("com.flashbuy.mapper")
@SpringBootApplication
@EnableAspectJAutoProxy(exposeProxy = true)
public class FlashbuyApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlashbuyApplication.class, args);
    }

}
