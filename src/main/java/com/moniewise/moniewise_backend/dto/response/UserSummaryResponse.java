package com.moniewise.moniewise_backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor // <--- This is the mechanic that fixes the "Cannot resolve constructor" error
@NoArgsConstructor
public class UserSummaryResponse {
    private String name;        // Display: "Sarah Johnson"
    private String username;    // Display: "@sarah_j"
    private String avatarUrl;   // Display: [Image]
    private String email;       // HIDDEN LOGIC: "sarah@gmail.com" <--- Add this back!
}