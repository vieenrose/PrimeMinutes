#include "diar_host.h"
#include <cstdio>
#include <cstdlib>
#include <vector>
#include <cstdint>
static std::vector<float> wav(const char*p){FILE*f=fopen(p,"rb");fseek(f,44,SEEK_SET);std::vector<int16_t>s(1<<26);size_t n=fread(s.data(),2,s.size(),f);fclose(f);std::vector<float>o(n);for(size_t i=0;i<n;i++)o[i]=s[i]/32768.f;return o;}
int main(int c,char**v){
 nemo::DiarHostConfig k; k.host_bin=v[1]; k.pre_f32=v[2]; k.tflite=v[3];
 auto pcm=wav(v[4]); nemo::DiarHost d(k);
 size_t piece=16000/10; 
 for(size_t o=0;o<pcm.size();o+=piece){size_t n=std::min(piece,pcm.size()-o);
  for(auto&t:d.push(pcm.data()+o,n))printf("ev %.3f %.3f speaker_%d %.3f\n",t.start_sample/16000.,t.end_sample/16000.,t.speaker,t.confidence);}
 for(auto&t:d.finish())printf("fin %.3f %.3f speaker_%d %.3f\n",t.start_sample/16000.,t.end_sample/16000.,t.speaker,t.confidence);
}
