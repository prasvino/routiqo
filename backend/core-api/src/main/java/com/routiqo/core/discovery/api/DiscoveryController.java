package com.routiqo.core.discovery.api;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
public class DiscoveryController {
    @GetMapping(value = "/api/v1/destinations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ClassPathResource destinations() { return new ClassPathResource("catalog/catalog.json"); }
}

