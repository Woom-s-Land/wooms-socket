package com.ee06.mychat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "wooms.socket.storage-secret=test-only-storage-secret-value-32chars")
class MychatApplicationTests {

    @Test
    void contextLoads() {
    }

}
