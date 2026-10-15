#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <unistd.h>
#include "qdl_runner.h"
static void *canc(void*a){ sleep(atoi((char*)a)); qdl_runner_cancel(); return NULL; }
static void cb(const char *l, void *u){ (void)u; printf("[line] %s\n", l); }
int main(int argc,char**argv){
  int fd=-1; int i=1; pthread_t t;
  if(argc>2 && !strcmp(argv[1],"--cancel-after")){ pthread_create(&t,NULL,canc,argv[2]); i=3; }
  if(argc>2 && !strcmp(argv[1],"--fd")){ fd=atoi(argv[2]); i=3; }
  char **av=calloc(argc+2,sizeof(char*)); int n=0; av[n++]="qdl";
  for(;i<argc;i++) av[n++]=argv[i];
  int rc=qdl_runner_run(fd,n,av,cb,NULL);
  printf("[exit] %d\n",rc); return 0; }
