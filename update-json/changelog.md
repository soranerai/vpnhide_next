## v1.0.0

### Added
- Deep redesign and optimization: Completely reworked interface (skeleton, async loading) and optimized code
- Flexible sorting: Added the ability to sort applications properly
- Hiding anonymous TUN routes: Exclusion of TUN from route requests
- Kernel-level bind bypass: Ability to route packets directly, bypassing any application binds at the kernel level
- Maximum stealth: Complete removal of /proc/ files accessible to all applications, eliminating module detection via the file system
