# Troubleshooting

| Symptom                      | Check                                                                                                                           |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| `/bootui` returns 404        | Use the `dev` or `local` profile, add DevTools, or set `bootui.enabled=ON`.                                                     |
| BootUI is disabled in `prod` | This is intentional; only `bootui.enabled=ON` can force activation with a disabled profile.                                     |
| Browser is rejected          | BootUI accepts loopback callers by default. Use `bootui.allow-non-localhost=true` only for a trusted local network.             |
| Spring Security blocks UI    | BootUI auto-registers a `/bootui/**` permit-all chain when Spring Security is active; check for a custom higher-priority chain. |
| A panel is empty             | Enable the relevant Actuator endpoint or optional Spring module; BootUI degrades to stable empty DTOs when data is unavailable. |
| Startup Timeline is empty    | Leave `bootui.startup.enabled=true` and `bootui.startup.capacity` greater than zero, or provide your own `BufferingApplicationStartup`. |
| Secrets are hidden           | Default exposure is `MASKED`; use `METADATA_ONLY` to hide all values or `FULL` only in trusted local sessions.                  |

## More resources

- [Feature details](./features) — panel-by-panel guide with screenshots
- [Configuration & safety](./configuration) — the safety model and common properties
- [Property reference](./properties) — every global and per-panel property
- [CHANGELOG](https://github.com/jdubois/boot-ui/blob/main/CHANGELOG.md) — release notes
- [CONTRIBUTING](https://github.com/jdubois/boot-ui/blob/main/CONTRIBUTING.md) — contributor workflow
- [SECURITY](https://github.com/jdubois/boot-ui/blob/main/SECURITY.md) — threat model and security policy
