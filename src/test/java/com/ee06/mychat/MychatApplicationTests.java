package com.ee06.mychat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "wooms.socket.storage-secret=test-only-storage-secret-value-32chars",
        "wooms.socket.access-revocation-enabled=false"
})
class MychatApplicationTests {

    @Test
    void contextLoads() {
    }

}
