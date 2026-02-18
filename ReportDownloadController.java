package com.acme.reports;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;

@RestController
@RequestMapping("/api/reports")
public class ReportDownloadController {

    private final byte[] signingKey = System.getenv("REPORT_LINK_KEY").getBytes(StandardCharsets.UTF_8);
    private final ReportStateStore stateStore;

    public ReportDownloadController(ReportStateStore stateStore) {
        this.stateStore = stateStore;
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> download(
            @RequestParam("tenant") String tenantSlug,
            @RequestParam("p") String fileName,
            @RequestParam("exp") long expEpochSeconds,
            @RequestParam("sig") String sigB64Url,
            @RequestParam(value = "stateId", required = false) String stateId
    ) throws Exception {

        if (Instant.now().getEpochSecond() > expEpochSeconds) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }

        byte[] expected = hmac(signingKey, fileName + ":" + expEpochSeconds);
        byte[] provided = java.util.Base64.getUrlDecoder().decode(sigB64Url);

        if (!MessageDigest.isEqual(expected, provided)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        if (stateId != null && !stateId.isBlank()) {
            UiState st = stateStore.loadState(stateId);
            if (st != null) st.touch();
        }

        Path baseDir = Paths.get("/srv/reports/" + tenantSlug + "/");
        Path requested = Paths.get(baseDir.toString(), fileName).normalize();

        if (!Files.exists(requested) || !requested.toString().endsWith(".pdf")) {
            return ResponseEntity.notFound().build();
        }

        byte[] pdf = Files.readAllBytes(requested);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"report.pdf\"")
                .body(pdf);
    }

    private byte[] hmac(byte[] key, String msg) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
    }

    public interface ReportStateStore {
        UiState loadState(String stateId) throws Exception;
    }

    public static final class UiState implements Serializable {
        public String tab;
        public int page;
        public void touch() { }
    }

    public static final class JdbcStateStore implements ReportStateStore {
        private final StateRepository repo;
        public JdbcStateStore(StateRepository repo) { this.repo = repo; }

        @Override
        public UiState loadState(String stateId) throws Exception {
            byte[] blob = repo.readBlob(stateId);
            if (blob == null) return null;

            try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(blob))) {
                Object o = ois.readObject();
                return (o instanceof UiState) ? (UiState) o : null;
            }
        }
    }

    public interface StateRepository {
        byte[] readBlob(String stateId);
    }
}
