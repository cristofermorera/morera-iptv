package nexo;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
class SourceSecrets {
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    SourceSecrets(SecretKey jwtKey) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(jwtKey);
        key = new SecretKeySpec(mac.doFinal("nexo-source-encryption-v1".getBytes(StandardCharsets.UTF_8)), "AES");
    }

    String seal(String text) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            var cipher = cipher(Cipher.ENCRYPT_MODE, nonce);
            byte[] encrypted = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(12 + encrypted.length).put(nonce).put(encrypted).array());
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    String open(String text) {
        try {
            var buffer = ByteBuffer.wrap(Base64.getDecoder().decode(text));
            byte[] nonce = new byte[12];
            buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            return new String(cipher(Cipher.DECRYPT_MODE, nonce).doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private Cipher cipher(int mode, byte[] nonce) throws Exception {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        return cipher;
    }
}
