package com.stockmind.bootstrap;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class EntryPageController {
    @GetMapping("/") public String entry() { return "redirect:/login.html"; }
}
