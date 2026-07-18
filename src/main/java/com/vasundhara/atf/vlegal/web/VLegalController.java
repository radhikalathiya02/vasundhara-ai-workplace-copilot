package com.vasundhara.atf.vlegal.web;

import com.vasundhara.atf.vlegal.dto.DocumentRequest;
import com.vasundhara.atf.vlegal.dto.DocumentResponse;
import com.vasundhara.atf.vlegal.dto.VerifyUrlRequest;
import com.vasundhara.atf.vlegal.dto.VerifyUrlResponse;
import com.vasundhara.atf.vlegal.service.LegalDocumentService;
import com.vasundhara.atf.vlegal.service.UrlVerifierService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vlegal")
@CrossOrigin(origins = "*")
public class VLegalController {

    private final LegalDocumentService docService;
    private final UrlVerifierService   verifierService;

    public VLegalController(LegalDocumentService docService, UrlVerifierService verifierService) {
        this.docService      = docService;
        this.verifierService = verifierService;
    }

    @PostMapping("/privacy/generate")
    public ResponseEntity<DocumentResponse> generatePrivacy(@Valid @RequestBody DocumentRequest req) {
        DocumentResponse resp = docService.generatePrivacyPolicy(req);
        return resp.getError() != null
                ? ResponseEntity.internalServerError().body(resp)
                : ResponseEntity.ok(resp);
    }

    @PostMapping("/terms/generate")
    public ResponseEntity<DocumentResponse> generateTerms(@Valid @RequestBody DocumentRequest req) {
        DocumentResponse resp = docService.generateTermsOfService(req);
        return resp.getError() != null
                ? ResponseEntity.internalServerError().body(resp)
                : ResponseEntity.ok(resp);
    }

    @PostMapping("/verify/url")
    public ResponseEntity<VerifyUrlResponse> verifyUrl(@RequestBody VerifyUrlRequest req) {
        VerifyUrlResponse resp = verifierService.verify(req);
        return resp.getError() != null
                ? ResponseEntity.internalServerError().body(resp)
                : ResponseEntity.ok(resp);
    }
}
