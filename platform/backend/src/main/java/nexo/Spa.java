package nexo;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class Spa {
    @GetMapping({"/login", "/recuperar", "/redefinir", "/configuracoes", "/jogos", "/admin"})
    String app() { return "forward:/index.html"; }
}

