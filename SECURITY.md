# Security Policy

## Supported Versions

At this time, we only support the latest version of Spectra. Please ensure you are running the most recent version of the code.

| Version | Supported          |
| ------- | ------------------ |
| latest  | :white_check_mark: |

## Reporting a Vulnerability

If you find a security vulnerability, please do not disclose it publicly. Instead, please report it via security@spectra-ai.local. We will acknowledge your report within 48 hours and provide a timeline for addressing the issue.

## Security Best Practices

To ensure the security of your Spectra instance, we recommend:
1. **Setting `SPECTRA_API_KEY`**: This enables API key authentication for all requests.
2. **Network Isolation**: Run Spectra within a private network and only expose the necessary ports.
3. **SSL/TLS Termination**: Use a reverse proxy (like Nginx or Caddy) with SSL/TLS to encrypt traffic to the frontend and API.
4. **Regular Updates**: Keep the Docker images and dependencies up to date to protect against known vulnerabilities.

We appreciate your help in keeping Spectra secure.
