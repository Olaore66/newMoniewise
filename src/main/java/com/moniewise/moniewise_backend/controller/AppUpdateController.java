package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.response.AppUpdateStatusResponse;
import com.moniewise.moniewise_backend.service.AppUpdateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/app")
public class AppUpdateController {

    private final AppUpdateService appUpdateService;

    public AppUpdateController(AppUpdateService appUpdateService) {
        this.appUpdateService = appUpdateService;
    }

    @GetMapping({"/version-check", "/update-status"})
    public ResponseEntity<AppUpdateStatusResponse> checkVersion(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String build,
            @RequestHeader(value = "X-App-Platform", required = false) String platformHeader,
            @RequestHeader(value = "X-App-Version", required = false) String versionHeader,
            @RequestHeader(value = "X-App-Build", required = false) String buildHeader) {

        String resolvedPlatform = firstNonBlank(platform, platformHeader);
        String resolvedVersion = firstNonBlank(version, versionHeader);
        String resolvedBuild = firstNonBlank(build, buildHeader);

        return ResponseEntity.ok(appUpdateService.checkUpdate(
                resolvedPlatform,
                resolvedVersion,
                resolvedBuild
        ));
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : fallback;
    }
}
