package com.booking.bookingsystem.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LoginSessionProperties.class)
public class LoginSessionConfiguration {
}
