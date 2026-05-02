#include <stdio.h>

int main() {
    char buf[] = "10388\n";
    char *line = buf;
    unsigned long uid = 0;
    while (*line >= '0' && *line <= '9') {
        uid = uid * 10 + (*line - '0');
        line++;
    }
    printf("uid = %lu\n", uid);
    return 0;
}
