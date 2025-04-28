package com.moniewise.moniewise_backend.dto.request;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import javax.validation.constraints.Past;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
// ProfileRequest (new DTO)
public class ProfileRequest {
    private BigDecimal monthlyIncome;
    private String mainExpense;
    private String savingsGoal;
    private String occupation;
    @Past
    private LocalDate dob;

    // Getters, setters
}
