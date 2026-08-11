package kr.hyuni.dialogue;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueWebApiTest {
    @Test
    void trustsOnlyTheLastProxyAddressAndAllowsAdminsToManageOtherOwners() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        assertEquals("203.0.113.9", DialogueWebApi.clientAddress(loopback, "198.51.100.2, 203.0.113.9").getHostAddress());

        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        assertTrue(DialogueWebApi.canAccessOwner(new WebPlayerSessions.Session(owner, "owner", Long.MAX_VALUE, false), owner));
        assertFalse(DialogueWebApi.canAccessOwner(new WebPlayerSessions.Session(owner, "owner", Long.MAX_VALUE, false), other));
        assertTrue(DialogueWebApi.canAccessOwner(new WebPlayerSessions.Session(owner, "admin", Long.MAX_VALUE, true), other));
    }
}
