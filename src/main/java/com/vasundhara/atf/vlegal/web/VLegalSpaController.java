package com.vasundhara.atf.vlegal.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class VLegalSpaController {

    @GetMapping("/vlegal")
    public String vlegal() {
        return "redirect:/vlegal/";
    }

    @GetMapping("/vlegal/")
    public String vlegalIndex() {
        return "forward:/vlegal/index.html";
    }
}
