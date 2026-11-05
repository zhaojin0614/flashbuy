package com.flashbuy.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class UserProfileDTO {

    @Size(max = 20, message = "昵称不能超过20个字符")
    private String nickName;

    @Size(max = 128, message = "个人介绍不能超过128个字符")
    private String introduce;

    @Size(max = 20, message = "城市不能超过20个字符")
    private String city;

    private Boolean gender;

    private LocalDate birthday;
}
