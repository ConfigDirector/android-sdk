# Signing key

Every AAR, jar and POM in the ConfigDirector Maven repository, `https://maven.configdirector.com`,
is signed with the key below. The signature is the `.asc` file next to each one.

|             |                                                     |
| ----------- | --------------------------------------------------- |
| Key ID      | `3CD40757E05C6B4C`                                  |
| Fingerprint | `779A DCFB 334D A3FD 2E00 5AB4 3CD4 0757 E05C 6B4C` |
| User ID     | `ConfigDirector <opensource@configdirector.com>`    |
| Created     | 2026-08-21                                          |
| Expires     | 2028-08-20                                          |

The key is published on keys.openpgp.org and keyserver.ubuntu.com, which Gradle's dependency
verification asks by default, and it is reproduced in full at the end of this page.

## Verify a file with gpg

```bash
gpg --keyserver hkps://keys.openpgp.org --recv-keys 779ADCFB334DA3FD2E005AB43CD40757E05C6B4C
gpg --verify android-sdk-1.7.0.aar.asc android-sdk-1.7.0.aar
```

The output must say the signature is good and name the fingerprint above.

## Pin the key in Gradle

In `gradle/verification-metadata.xml`, trust the key for the `com.configdirector` group:

```xml
<trusted-keys>
  <trusted-key id="779ADCFB334DA3FD2E005AB43CD40757E05C6B4C" group="com.configdirector"/>
</trusted-keys>
```

How to set up dependency verification is in the
[Gradle documentation](https://docs.gradle.org/current/userguide/dependency_verification.html).

## Public key

```
-----BEGIN PGP PUBLIC KEY BLOCK-----

mQINBGqI5AIBEACtzOECruBCsvslo5Nb0t/QnVt+5y+J50JhbRAscNfFuV9B1hNH
E3hS3QuGB+qTUtk0k28RvbCWMLy0S2TLQhIDu6YdBOg57gTtDsuy8qpL23pTAw4I
R3h+C9XjegVEWRG/A+w4nmz/lghKIqOMIM8ehR9Ulyk5A1TM0LzzxOsw5dCNgoeH
CP3fKubB4Qc4fcodNBE9ifHoou+Q4C6cNBLwy10Rd4Czbo23vXagHFVeMtVP7jjm
Pd7MQj7CA7gL1nnNX3NB55SpHnYZuOy8Dh0PCVKYwW80YUkQOL1lO7f+EP1Jj03l
9pVrlNxON+i1rRxDBsDc2cfmP+o42nBVibiIRMwk/QT8wtf86ugQ+q7gvwVss0sV
fiMcsVU2ZzBRdgh9s8m7h1CIOr/HnGf9Q0K0wL2UoaZSkuI/rRMyNBm3lRiWWlMp
3ngVqIuFz2lOtA1duiMsiSon1zc9Y3E48cvJOV5QUCGPVcp4dOBIFeR+KQvNggHN
mZo8hQ/ZCqNLSqgyx+onN6+t5A5cpd1EuvPFyfzgq82KK52+Knn99J3YQHj0+RoH
EKk2MG6vxghhgjUgl3D1vViNU1IQs1CCo7splyUznf4kQRUcSgBZXGR99XyQaWfx
vzib22jYf2uhT3BDM7rrxBJT36VOnMzuNYwtJ/F/MiiutpUT8Kft5ueXdQARAQAB
tC5Db25maWdEaXJlY3RvciA8b3BlbnNvdXJjZUBjb25maWdkaXJlY3Rvci5jb20+
iQJzBBMBCABdFiEEd5rc+zNNo/0uAFq0PNQHV+Bca0wFAmqI5AIbFIAAAAAABAAO
bWFudTIsMi41KzEuMTIsMCwzAhsDBQkDwmcABQsJCAcCAiICBhUKCQgLAgQWAgMB
Ah4HAheAAAoJEDzUB1fgXGtMyrUP/2YcEsdcYmyOIcOiY49ogHKoiGHaB7BSO7Ys
/Gt/EP8zx+6VmCqFWDrzcyA1SSFA+CYSVi8NHTqn42S9WldtSa//8plMWXBuLlcG
PwZZs5kG018sOPKyi2KqueitXs60WKXRvYKM6O5oe51mMFCsbjjlrHYPYf1SFEo/
Gt8Sq+9ScW8ihjQT5CKLrTBZEYPW2vSNmXhB9RWeUCs7Ti3fTevtRAs3QiLEwK8N
KrypkAjIsux4EdX5YLfic5Se8tFpFXVohADvge/Bc6XXgPmMVQw3yc97nFm7/5Z7
CFAlJ2FfzTBbRBUSlLRjq4CM2lV+ZhX0fAT8aqLcmwFZLBv076KM58SUGda3p140
D8Z46wigXw7vTtrA5xODcdVBZgcedBDyCaypN/UMytDPccXKmG6v4p1qhSFA6rDF
82j1oRpmxHUKh6WPfVlJFQHWCoBCMMxN+lMv4NIcBHRQ43M8K6YUpOzXZzPv0mO4
CzbGrtrfmnDL8MdZIpL15INxKJWmqaS0JHoQlceJwkZ4LUyW8vlJFsezv9goFVPi
7kxXKr+waDcbBP2XcsQHzw097zlztDb7jNKV8g3BnjwYIX2S8laSzj9WSAmieAX4
VxwtOPOzXmakbvn87e1zPmGGKVLsCqXrwrSrTSCj9dEiOeLxIUZ60fmy8Se3lADZ
qqPiLO2O
=VeTh
-----END PGP PUBLIC KEY BLOCK-----
```
