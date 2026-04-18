package fr.spectra.controller;

import fr.spectra.service.IngestionService;
import fr.spectra.service.UrlIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration HTTP de l'ingestion de fichiers ZIP via IngestController.
 * L'IngestionService est mocké — aucune extraction réelle n'est effectuée.
 */
@WebMvcTest(IngestController.class)
class IngestControllerZipTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IngestionService ingestionService;

    @MockBean
    private UrlIngestionService urlIngestionService;

    // ── Upload ZIP ────────────────────────────────────────────────────────────

    @Test
    void postIngest_zipFile_returns200WithTaskId() throws Exception {
        when(ingestionService.submit(anyList())).thenReturn("task-zip-001");

        mockMvc.perform(multipart("/api/ingest")
                        .file(mockZip("data.zip", "sample.json", "[{\"key\":\"val\"}]")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.taskId").value("task-zip-001"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void postIngest_zipWithContentType_returns200() throws Exception {
        when(ingestionService.submit(anyList())).thenReturn("task-zip-002");

        MockMultipartFile file = new MockMultipartFile(
                "files", "archive.zip", "application/zip", buildZip("payload.json", "[{}]"));

        mockMvc.perform(multipart("/api/ingest").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-zip-002"));
    }

    @Test
    void postIngest_zipWithOctetStream_returns200() throws Exception {
        when(ingestionService.submit(anyList())).thenReturn("task-zip-003");

        MockMultipartFile file = new MockMultipartFile(
                "files", "archive.zip", "application/octet-stream", buildZip("data.xml", "<root/>"));

        mockMvc.perform(multipart("/api/ingest").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-zip-003"));
    }

    @Test
    void postIngest_noFiles_returns400() throws Exception {
        mockMvc.perform(multipart("/api/ingest"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postIngest_multipleFiles_includesZip_returns200() throws Exception {
        when(ingestionService.submit(anyList())).thenReturn("task-multi-001");

        MockMultipartFile json = new MockMultipartFile(
                "files", "extra.json", "application/json", "[{\"a\":1}]".getBytes());
        MockMultipartFile zip = mockZip("batch.zip", "inner.json", "[{\"b\":2}]");

        mockMvc.perform(multipart("/api/ingest").file(json).file(zip))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-multi-001"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private MockMultipartFile mockZip(String zipName, String entryName, String content) throws Exception {
        return new MockMultipartFile("files", zipName, "application/zip", buildZip(entryName, content));
    }

    private byte[] buildZip(String entryName, String content) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(content.getBytes());
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
