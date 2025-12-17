import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Supplier;

public class ParentalPasswordGuard {
    private static final String KDF = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LEN = 256;
    private static final int SALT_LEN = 16;

    private final Path passwordFile;
    private final SecureRandom random = new SecureRandom();

    public ParentalPasswordGuard() {
        String appDir = System.getenv("APPDATA");
        if (appDir == null || appDir.isBlank()) {
            appDir = System.getProperty("user.home");
        }
        Path dir = Path.of(appDir, "ParentalControlApp");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        passwordFile = dir.resolve("parental_pwd.txt");
    }

    public boolean isPasswordSet() {
        return Files.exists(passwordFile);
    }

    public void setPassword(char[] password) throws IOException {
        Objects.requireNonNull(password, "password");
        byte[] salt = new byte[SALT_LEN];
        random.nextBytes(salt);
        byte[] hash = hash(password, salt);
        String combined = HexFormat.of().formatHex(salt) + ":" + HexFormat.of().formatHex(hash);
        Files.writeString(passwordFile, combined, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Arrays.fill(password, '\0');
        Arrays.fill(hash, (byte) 0);
    }

    public boolean verifyPassword(char[] attempt) throws IOException {
        if (!isPasswordSet()) {
            return false;
        }
        Objects.requireNonNull(attempt, "attempt");
        try {
            String stored = Files.readString(passwordFile).trim();
            if (stored.isEmpty()) return false;
            String[] parts = stored.split(":");
            if (parts.length != 2) return false;
            byte[] salt = HexFormat.of().parseHex(parts[0]);
            byte[] expected = HexFormat.of().parseHex(parts[1]);
            byte[] actual = hash(attempt, salt);
            return slowEquals(expected, actual);
        } finally {
            Arrays.fill(attempt, '\0');
        }
    }

    public void requirePassword(ProtectedAction action, Supplier<char[]> passwordSupplier) {
        if (!isPasswordSet()) {
            throw new IllegalStateException("Nu există parolă setată pentru " + action);
        }
        char[] provided = passwordSupplier.get();
        if (provided == null) {
            throw new SecurityException("Autentificare anulată pentru " + action);
        }
        try {
            if (!verifyPassword(provided)) {
                throw new SecurityException("Parola introdusă este incorectă pentru " + action);
            }
        } catch (IOException e) {
            throw new SecurityException("Nu pot verifica parola pentru " + action, e);
        }
    }

    private byte[] hash(char[] password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_LEN);
            return SecretKeyFactory.getInstance(KDF).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Nu s-a putut calcula hash-ul parolei", e);
        }
    }

    private boolean slowEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
