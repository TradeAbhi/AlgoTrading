package com.trading.algo.controller;

import com.trading.algo.service.SmsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SmsController {

    private final SmsService smsService;

    public SmsController(SmsService smsService) {
        this.smsService = smsService;
    }

    @GetMapping("/send-sms")
    public String send(@RequestParam String msg, @RequestParam String phone) {
        return smsService.sendSms(msg, phone);
    }
}