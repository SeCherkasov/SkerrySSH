package app.skerry.shared.vault

/**
 * Deterministic test SSH certificates issued by `ssh-keygen` from a single test CA. Used by both
 * the inspector ([SshjCertificateInspectorTest]) and cert authentication (`SshjTransport`).
 * Fixed generation parameters:
 *  - CA: ed25519, fingerprint [CA_FINGERPRINT];
 *  - ED25519 certificate: key id `skerry-test@ed25519`, principals `alice,deploy`, serial 42;
 *  - RSA-2048 certificate: key id `skerry-test@rsa`, principal `bob`, serial 7;
 *  - validity window for both: 2024-01-01 to 2034-01-01 (UTC).
 *
 * Keys are throwaway test material only, used nowhere else.
 */
object CertificateFixtures {

    const val CA_FINGERPRINT = "SHA256:wOuEBTY+A3Mt14uVi/GzWzxjVBHF5EcofV4jkBfdcTM"

    /** CA public key (`ca.pub`), the trust anchor for server-side certificate validation in tests. */
    const val CA_PUBLIC_KEY =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMaQgzqhP+ZzyG6dpQhjVq8kYqyd8kHrJugsGwQ2JDSQ skerry-ca"

    /** Principal [ED25519_CERT] is issued for (the name the cert authenticates as on the server). */
    const val ED25519_PRINCIPAL = "alice"

    /** Unbounded certificate (`valid before` == uint64 max), for testing the "forever" branch. */
    const val FOREVER_CERT =
        "ssh-ed25519-cert-v01@openssh.com AAAAIHNzaC1lZDI1NTE5LWNlcnQtdjAxQG9wZW5zc2guY29tAAAAIPt1kAPPUy8QuXwgRDbMxMYEUaHpvZkGKnhsAgwy5sydAAAAIIeYr544sT/dKZMQfPaZB6tRNa4rXSDbJewJ5GaoGEMnAAAAAAAAAGMAAAABAAAAE3NrZXJyeS10ZXN0QGZvcmV2ZXIAAAAHAAAAA3N2YwAAAAAAAAAA//////////8AAAAAAAAAggAAABVwZXJtaXQtWDExLWZvcndhcmRpbmcAAAAAAAAAF3Blcm1pdC1hZ2VudC1mb3J3YXJkaW5nAAAAAAAAABZwZXJtaXQtcG9ydC1mb3J3YXJkaW5nAAAAAAAAAApwZXJtaXQtcHR5AAAAAAAAAA5wZXJtaXQtdXNlci1yYwAAAAAAAAAAAAAAMwAAAAtzc2gtZWQyNTUxOQAAACDGkIM6oT/mc8hunaUIY1avJGKsnfJB6yboLBsENiQ0kAAAAFMAAAALc3NoLWVkMjU1MTkAAABAue/GyA1Dl+VcwlHH763BsW9oYmixnT6Gc8wqzpIIJ/1wWUF76XDfd7Ra5nudp9KO3voGJnltpc1FbXKYjYFBAw== alice@skerry"

    val ED25519_PRIVATE_KEY = """
        -----BEGIN OPENSSH PRIVATE KEY-----
        b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
        QyNTUxOQAAACCHmK+eOLE/3SmTEHz2mQerUTWuK10g2yXsCeRmqBhDJwAAAJCTquJek6ri
        XgAAAAtzc2gtZWQyNTUxOQAAACCHmK+eOLE/3SmTEHz2mQerUTWuK10g2yXsCeRmqBhDJw
        AAAECj4nk0xG00zyQDEYjZzkq4DYaRGzTDQCa722CqWQsnKIeYr544sT/dKZMQfPaZB6tR
        Na4rXSDbJewJ5GaoGEMnAAAADGFsaWNlQHNrZXJyeQE=
        -----END OPENSSH PRIVATE KEY-----
    """.trimIndent() + "\n"

    const val ED25519_CERT =
        "ssh-ed25519-cert-v01@openssh.com AAAAIHNzaC1lZDI1NTE5LWNlcnQtdjAxQG9wZW5zc2guY29tAAAAIJ/XTmChh23PUo43PsVebZVnBUh9yVb7r8UgCo6MD2XGAAAAIIeYr544sT/dKZMQfPaZB6tRNa4rXSDbJewJ5GaoGEMnAAAAAAAAACoAAAABAAAAE3NrZXJyeS10ZXN0QGVkMjU1MTkAAAATAAAABWFsaWNlAAAABmRlcGxveQAAAABlkgCAAAAAAHhh+AAAAAAAAAAAggAAABVwZXJtaXQtWDExLWZvcndhcmRpbmcAAAAAAAAAF3Blcm1pdC1hZ2VudC1mb3J3YXJkaW5nAAAAAAAAABZwZXJtaXQtcG9ydC1mb3J3YXJkaW5nAAAAAAAAAApwZXJtaXQtcHR5AAAAAAAAAA5wZXJtaXQtdXNlci1yYwAAAAAAAAAAAAAAMwAAAAtzc2gtZWQyNTUxOQAAACDGkIM6oT/mc8hunaUIY1avJGKsnfJB6yboLBsENiQ0kAAAAFMAAAALc3NoLWVkMjU1MTkAAABAwycZAnZtpvGb6wZDhWCcA6sa4Lz7sieexLCRkC7VNcZj23iiqej1B135atUIc0G7yR/g/TIzACfk2G3DHOYLAA== alice@skerry"

    val RSA_PRIVATE_KEY = """
        -----BEGIN OPENSSH PRIVATE KEY-----
        b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAABFwAAAAdzc2gtcn
        NhAAAAAwEAAQAAAQEApYxAybDjxfz3Fs7IZNJDn4Nv/xegr8mbJPjCP+Q8N8GT1Xef6h+z
        6f8UZ7w22jMCxLwnv0J5l9qeQ+pc/siJRjG+vPicDxCuVuz2t9gxdaX7u1ZAFjihosvPqT
        npHRjGWdPdCghFIlTYUTmtqYRgM8b8ypQi6O6R+RWXgZBzAurxSEBbpcMZPcVv+UglMeFL
        4q3nCFJitbOXoeXWUf7qj58ccsFZSynYaZupzzFg8vR6Q2Bb9dT5ptDXatbSokDqTQnha9
        lxKXrPILhdA8L0XuUhwq7tLULoN9XOJ1Kajz5SHbPbkR3q0Xgida4nQwIDEA95WGYwvHki
        WiwyUfxSiQAAA8D05Ynr9OWJ6wAAAAdzc2gtcnNhAAABAQCljEDJsOPF/PcWzshk0kOfg2
        //F6CvyZsk+MI/5Dw3wZPVd5/qH7Pp/xRnvDbaMwLEvCe/QnmX2p5D6lz+yIlGMb68+JwP
        EK5W7Pa32DF1pfu7VkAWOKGiy8+pOekdGMZZ090KCEUiVNhROa2phGAzxvzKlCLo7pH5FZ
        eBkHMC6vFIQFulwxk9xW/5SCUx4UvirecIUmK1s5eh5dZR/uqPnxxywVlLKdhpm6nPMWDy
        9HpDYFv11Pmm0Ndq1tKiQOpNCeFr2XEpes8guF0DwvRe5SHCru0tQug31c4nUpqPPlIds9
        uRHerReCJ1ridDAgMQD3lYZjC8eSJaLDJR/FKJAAAAAwEAAQAAAQAJ4oxKzshm/dIeN0Yx
        ePmnOHCzTWyfmnzsUfs9V+o9lQ44AJ7YmsCCMCQ+hndLA6E+cJK6AaTji58IJKIdZ4mE2r
        sOKxUcdC1IzPj1ZNAdO4ZCuyVz/jkukJdrfiT8gwpy+irYasKACItrHDPRq7EX3mGhUgOc
        n4QxWJf1mVO4wIhyS2qTni+IQWf4P1fp3PuPUDmFlIwb02UcL09Q9E5VGvgj6Hg+aMXABg
        a5NjD1bZh3oY/mWur8fBKMKZ70ugNKee+nTiUykEGF9IyzHp/a2n0uiG6NDY6cbvVM637u
        orHFyMHLa1gzgO7AaXyIJmA5PMUaIau6feq9zfIc7qmFAAAAgQCd+sApkIqf1Z4Zf1t4zL
        2dYjw3mW0dhdBAGOCHTxiErBOUvfe/ZE/r4iggC1EAfdYfnX5V2AD3Xd13bkomVkXjUvJQ
        yzm0mryrFDG80KG5fIz1ohiT9zMg1nD5SPIlRthOcfqUB2VDZPRhn9y5VEf/paL5LJEYo0
        uZOkq9+0fkEgAAAIEA3wfPo+67qQtVALYrmSpeoneVOcaOnkBGrKmQZW5w3IyB4Ruj+Ol6
        +7EIZY9+jmAvKD1LjUWlmeZRKjHcsReT8P8cgcENtViZUbpPITdNm/YbDzJR05Rj4uVvx5
        L9XhGit8eawPheDHEqhBS/wtEIeOTVFWwrccsN+CVX4Xt1KCUAAACBAL4FHUz/k+HucL85
        /3EDpCDnGG0HRpvkGPDR32zp7NxIiVYrhbWhUW8LHZxzUcudYaU4utaTm+XVhutv0dd3BP
        YAX/I43aiA9V8Y6vLpPN/Pr9qIA+3AT8aRzRgSEJbGvNukWnO8DNehsmVcKAvb+b6HeA7a
        I8KlGR1N6+vGI5GVAAAACmJvYkBza2Vycnk=
        -----END OPENSSH PRIVATE KEY-----
    """.trimIndent() + "\n"

    const val RSA_CERT =
        "ssh-rsa-cert-v01@openssh.com AAAAHHNzaC1yc2EtY2VydC12MDFAb3BlbnNzaC5jb20AAAAg5yqU1qd4RoLNmDUev5J8vMXA4HA/9/Znc20eoRL98u8AAAADAQABAAABAQCljEDJsOPF/PcWzshk0kOfg2//F6CvyZsk+MI/5Dw3wZPVd5/qH7Pp/xRnvDbaMwLEvCe/QnmX2p5D6lz+yIlGMb68+JwPEK5W7Pa32DF1pfu7VkAWOKGiy8+pOekdGMZZ090KCEUiVNhROa2phGAzxvzKlCLo7pH5FZeBkHMC6vFIQFulwxk9xW/5SCUx4UvirecIUmK1s5eh5dZR/uqPnxxywVlLKdhpm6nPMWDy9HpDYFv11Pmm0Ndq1tKiQOpNCeFr2XEpes8guF0DwvRe5SHCru0tQug31c4nUpqPPlIds9uRHerReCJ1ridDAgMQD3lYZjC8eSJaLDJR/FKJAAAAAAAAAAcAAAABAAAAD3NrZXJyeS10ZXN0QHJzYQAAAAcAAAADYm9iAAAAAGWSAIAAAAAAeGH4AAAAAAAAAACCAAAAFXBlcm1pdC1YMTEtZm9yd2FyZGluZwAAAAAAAAAXcGVybWl0LWFnZW50LWZvcndhcmRpbmcAAAAAAAAAFnBlcm1pdC1wb3J0LWZvcndhcmRpbmcAAAAAAAAACnBlcm1pdC1wdHkAAAAAAAAADnBlcm1pdC11c2VyLXJjAAAAAAAAAAAAAAAzAAAAC3NzaC1lZDI1NTE5AAAAIMaQgzqhP+ZzyG6dpQhjVq8kYqyd8kHrJugsGwQ2JDSQAAAAUwAAAAtzc2gtZWQyNTUxOQAAAECFycSjaQaVk6msdusVDPEw0ODXRkyZ9nCN6RcOjOX7UMPTFEyCx8NGEcyxqb/bGhJwicwG+ArQ5cCT6q+mNQ8B bob@skerry"
}
