package org.homevision.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/opencv")
public class CommandController {
    @GetMapping("/start/{param}")
    public String startOpenCV(@PathVariable int param) {
        return "Started";
    }

    @GetMapping("/stop")
    public String stopOpenCV() {
        return "Started";
    }
}
