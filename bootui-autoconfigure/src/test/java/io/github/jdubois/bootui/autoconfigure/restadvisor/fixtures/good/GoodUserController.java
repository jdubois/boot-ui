package io.github.jdubois.bootui.autoconfigure.restadvisor.fixtures.good;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A clean, well-designed controller that should trip no REST API Advisor rules. */
@RestController
@Validated
@RequestMapping("/api/v1/users")
public class GoodUserController {

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<UserDto> listUsers(Pageable pageable) {
        return Page.empty(pageable);
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public UserDto getUser(@PathVariable String id) {
        return new UserDto(id, "user");
    }

    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto createUser(@Validated @RequestBody CreateUserRequest request) {
        return new UserDto("1", request.name());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable String id) {
        // no-op
    }
}
