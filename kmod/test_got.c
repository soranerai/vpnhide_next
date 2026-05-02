#pragma GCC visibility push(hidden)
void hook_wrap(void);
#pragma GCC visibility pop

void my_init(void) {
    hook_wrap();
}
