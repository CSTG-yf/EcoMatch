package com.tencent.supersonic.headless.server.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.server.pojo.bank.BankSemanticImportConfig;
import com.tencent.supersonic.headless.server.pojo.bank.BankSemanticImportReport;
import com.tencent.supersonic.headless.server.service.bank.BankSemanticImportException;
import com.tencent.supersonic.headless.server.service.bank.BankSemanticImportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/semantic/bank/resources")
public class BankSemanticImportController {

    private final BankSemanticImportService importService;

    public BankSemanticImportController(BankSemanticImportService importService) {
        this.importService = importService;
    }

    @PostMapping(value = "/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BankSemanticImportReport validate(@RequestPart("file") MultipartFile file)
            throws IOException {
        return importService.validate(file.getBytes(), file.getOriginalFilename());
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BankSemanticImportReport importWorkbook(@RequestPart("file") MultipartFile file,
            @ModelAttribute BankSemanticImportConfig config, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        User user = UserHolder.findUser(request, response);
        try {
            return importService.importWorkbook(file.getBytes(), file.getOriginalFilename(), config,
                    user);
        } catch (BankSemanticImportException e) {
            return e.getReport();
        }
    }
}
