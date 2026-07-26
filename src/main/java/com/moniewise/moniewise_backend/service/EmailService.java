package com.moniewise.moniewise_backend.service;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;

@Service
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private TemplateEngine templateEngine;

    @Async // 🚀 FIX: Runs in background to prevent Connection Leak
    public void sendPasswordResetEmail(String to, String userName, String resetLink) {
        try {
            // 🔍 DEBUG: Log the parameters to ensure they are not null
            logger.info("⚡ Preparing email for: {}", to);
            logger.info("   - User Name: {}", userName);
            logger.info("   - Reset Link: {}", resetLink);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            // 1. Set Parameters into Thymeleaf Context
            Context context = new Context();
            context.setVariable("userName", userName);  // Must match <span th:text="${userName}">
            context.setVariable("resetLink", resetLink); // Must match <a th:href="${resetLink}">

            // 2. Process the Template
            // Ensure file exists at: src/main/resources/templates/reset-password.html
            String htmlContent = templateEngine.process("reset-password", context);

            // 3. Configure Email
            helper.setTo(to);
            helper.setSubject("Reset Your Password");
            helper.setText(htmlContent, true); // true = HTML
            helper.setFrom("support@wisemonie.app");
            try {
                org.springframework.core.io.ClassPathResource logo =
                    new org.springframework.core.io.ClassPathResource("images/main logo white background.png");
                if (logo.exists()) helper.addInline("wisemonie-logo", logo, "image/png");
            } catch (Exception ignored) {}

            // 4. Send
            mailSender.send(message);
            logger.info("✅ Email successfully sent to {}", to);

        } catch (MessagingException e) {
            logger.error("❌ Mail Error: {}", e.getMessage());
        } catch (Exception e) {
            // Catches TemplateInputException if the HTML file is missing
            logger.error("❌ General Error: {}", e.getMessage());
            e.printStackTrace();
        }
    }
}