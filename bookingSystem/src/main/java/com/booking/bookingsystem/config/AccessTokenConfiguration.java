package com.booking.bookingsystem.config;

import com.booking.bookingsystem.security.AccessTokenClaimsValidator;
import com.booking.bookingsystem.service.AccessTokenService;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "booking.auth.jwt.enabled", havingValue = "true")
@EnableConfigurationProperties(AccessTokenProperties.class)
public class AccessTokenConfiguration {
    @Bean
    public AccessTokenService accessTokenService(AccessTokenProperties properties, Clock clock) throws IOException {
        try (var privateInput = properties.privateKey().getInputStream();
             var publicInput = properties.publicKey().getInputStream()) {
            var privateKey = RsaKeyConverters.pkcs8().convert(privateInput);
            var publicKey = RsaKeyConverters.x509().convert(publicInput);
            if (privateKey == null || publicKey == null || publicKey.getModulus().bitLength() < 2048
                    || !privateKey.getModulus().equals(publicKey.getModulus())) {
                throw new IllegalArgumentException("JWT requires a matching RSA key pair of at least 2048 bits");
            }
            var jwk = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
            var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
            var decoder = NimbusJwtDecoder.withPublicKey(publicKey)
                    .signatureAlgorithm(SignatureAlgorithm.RS256).validateType(false).build();
            // Replace default timestamp tolerance with our explicit claims and exact deadline policy.
            decoder.setJwtValidator(new AccessTokenClaimsValidator(properties, clock));
            return new AccessTokenService(encoder, decoder, properties, clock);
        }
    }
}
