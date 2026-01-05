# Documentation


### 📑 Table of Contents
- [Execution Pipeline](#execution-pipeline)
- [Code Architecture](#code-architecture)
    - [MainActivity](#mainactivity)
- [Key Methods](#key-methods)

---


## Execution Pipeline

The application follows a strict initialization sequence to ensure hardware and network resources are managed correctly.

```mermaid
flowchart TD
    A[MainActivity] -->|Start Service| B(ServiceManager)--> C(AuthLogin)

    C -->|if Success| D[ConnManager]
    C -->|If failure| E[returns]

    F[ConnManager] -->|Inits Ws & clientInfoProvider| G(ConnRouter)

```




## Code Architecture


---
