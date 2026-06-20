// ECDH B163 - ported from Gadgetbridge (AGPL-3.0)
// Original: https://codeberg.org/Freeyourgadget/Gadgetbridge
// Original port of tiny-ECDH-c: https://github.com/kokke/tiny-ECDH-c
package com.dopamincheker.band;

public class ECDH_B163 {
    static final int CURVE_DEGREE = 163;
    static final int ECC_PRV_KEY_SIZE = 24;
    static final int ECC_PUB_KEY_SIZE = 2 * ECC_PRV_KEY_SIZE;
    static final int BITVEC_MARGIN = 3;
    static final int BITVEC_NBITS = (CURVE_DEGREE + BITVEC_MARGIN);
    static final int BITVEC_NWORDS = ((BITVEC_NBITS + 31) / 32);
    static final int BITVEC_NBYTES = (4 * BITVEC_NWORDS);

    static final int[] polynomial  = {0x000000c9,0x00000000,0x00000000,0x00000000,0x00000000,0x00000008};
    static final int[] coeff_b     = {0x4a3205fd,0x512f7874,0x1481eb10,0xb8c953ca,0x0a601907,0x00000002};
    static final int[] base_x      = {0xe8343e36,0xd4994637,0xa0991168,0x86a2d57e,0xf0eba162,0x00000003};
    static final int[] base_y      = {0x797324f1,0xb11c5c0c,0xa2cdd545,0x71a0094f,0xd51fbc6c,0x00000000};
    static final int[] base_order  = {0xa4234c33,0x77e70c12,0x000292fe,0x00000000,0x00000000,0x00000004};

    static int bitvec_get_bit(final int[] x, final int idx) {
        return (int)((((((long)x[idx/32]&0xffffffffL)>>(idx&31))&1)));
    }
    static void bitvec_clr_bit(final int[] x, final int idx) { x[idx/32] &= ~(1<<(idx&31)); }
    static void bitvec_copy(int[] x, int[] y) { for (int i=0;i<BITVEC_NWORDS;++i) x[i]=y[i]; }
    static void bitvec_swap(int[] x, int[] y) { int[] t=new int[BITVEC_NWORDS]; bitvec_copy(t,x); bitvec_copy(x,y); bitvec_copy(y,t); }
    static boolean bitvec_equal(final int[] x, final int[] y) { for (int i=0;i<BITVEC_NWORDS;++i) if(x[i]!=y[i]) return false; return true; }
    static void bitvec_set_zero(int[] x) { for (int i=0;i<BITVEC_NWORDS;++i) x[i]=0; }
    static boolean bitvec_is_zero(final int[] x) { int i=0; while(i<BITVEC_NWORDS){if(x[i]!=0)break;i+=1;} return(i==BITVEC_NWORDS); }

    static int bitvec_degree(final int[] x) {
        int i = BITVEC_NWORDS*32;
        int y = BITVEC_NWORDS;
        while((i>0)&&(x[--y]==0)) i-=32;
        if(i!=0){int u32mask=((int)1<<31); while(((x[y])&u32mask)==0){u32mask=(int)(((long)u32mask&0xffffffffL)>>1);i-=1;}}
        return i;
    }
    static void bitvec_lshift(int[] x, final int[] y, int nbits) {
        int nwords=(nbits/32); int i,j;
        for(i=0;i<nwords;++i) x[i]=0;
        j=0; while(i<BITVEC_NWORDS){x[i]=y[j];i+=1;j+=1;}
        nbits&=31;
        if(nbits!=0){for(i=(BITVEC_NWORDS-1);i>0;--i) x[i]=(int)(((long)(x[i])<<nbits)|(((long)x[i-1]&0xffffffffL)>>(32-nbits))); x[0]=(int)((long)(x[0])<<nbits);}
    }
    static void gf2field_set_one(int[] x) { x[0]=1; for(int i=1;i<BITVEC_NWORDS;++i) x[i]=0; }
    static boolean gf2field_is_one(int[] x) { if(x[0]!=1)return false; for(int i=1;i<BITVEC_NWORDS;++i) if(x[i]!=0)break; int i=1; while(i<BITVEC_NWORDS&&x[i]==0)i++; return(i==BITVEC_NWORDS); }
    static void gf2field_add(int[] z, final int[] x, final int[] y) { for(int i=0;i<BITVEC_NWORDS;++i) z[i]=(x[i]^y[i]); }
    static void gf2field_inc(int[] x) { x[0]^=1; }
    static void gf2field_mul(int[] z, final int[] x, final int[] y) {
        int i; int[] tmp=new int[BITVEC_NWORDS];
        bitvec_copy(tmp,x);
        if(bitvec_get_bit(y,0)!=0) bitvec_copy(z,x); else bitvec_set_zero(z);
        for(i=1;i<CURVE_DEGREE;++i){
            bitvec_lshift(tmp,tmp,1);
            if(bitvec_get_bit(tmp,CURVE_DEGREE)!=0) gf2field_add(tmp,tmp,polynomial);
            if(bitvec_get_bit(y,i)!=0) gf2field_add(z,z,tmp);
        }
    }
    static void gf2field_inv(int[] z, final int[] x) {
        int[] u=new int[BITVEC_NWORDS],v=new int[BITVEC_NWORDS],g=new int[BITVEC_NWORDS],h=new int[BITVEC_NWORDS];
        bitvec_copy(u,x); bitvec_copy(v,polynomial); bitvec_set_zero(g); gf2field_set_one(z);
        while(!gf2field_is_one(u)){
            int i=(bitvec_degree(u)-bitvec_degree(v));
            if(i<0){bitvec_swap(u,v);bitvec_swap(g,z);i=-i;}
            bitvec_lshift(h,v,i); gf2field_add(u,u,h);
            bitvec_lshift(h,g,i); gf2field_add(z,z,h);
        }
    }
    static void gf2point_copy(int[] x1,int[] y1,final int[] x2,final int[] y2){bitvec_copy(x1,x2);bitvec_copy(y1,y2);}
    static void gf2point_set_zero(int[] x,int[] y){bitvec_set_zero(x);bitvec_set_zero(y);}
    static boolean gf2point_is_zero(final int[] x,final int[] y){return(bitvec_is_zero(x)&&bitvec_is_zero(y));}
    static void gf2point_double(int[] x,int[] y){
        if(bitvec_is_zero(x)){bitvec_set_zero(y);}
        else{int[] l=new int[BITVEC_NWORDS];gf2field_inv(l,x);gf2field_mul(l,l,y);gf2field_add(l,l,x);gf2field_mul(y,x,x);gf2field_mul(x,l,l);gf2field_inc(l);gf2field_add(x,x,l);gf2field_mul(l,l,x);gf2field_add(y,y,l);}
    }
    static void gf2point_add(int[] x1,int[] y1,final int[] x2,final int[] y2){
        if(!gf2point_is_zero(x2,y2)){
            if(gf2point_is_zero(x1,y1)){gf2point_copy(x1,y1,x2,y2);}
            else{
                if(bitvec_equal(x1,x2)){if(bitvec_equal(y1,y2))gf2point_double(x1,y1);else gf2point_set_zero(x1,y1);}
                else{int[] a=new int[BITVEC_NWORDS],b=new int[BITVEC_NWORDS],c=new int[BITVEC_NWORDS],d=new int[BITVEC_NWORDS];
                    gf2field_add(a,y1,y2);gf2field_add(b,x1,x2);gf2field_inv(c,b);gf2field_mul(c,c,a);gf2field_mul(d,c,c);gf2field_add(d,d,c);gf2field_add(d,d,b);gf2field_inc(d);gf2field_add(x1,x1,d);gf2field_mul(a,x1,c);gf2field_add(a,a,d);gf2field_add(y1,y1,a);bitvec_copy(x1,d);}
            }
        }
    }
    static void gf2point_mul(int[] x,int[] y,final int[] exp){
        int[] tmpx=new int[BITVEC_NWORDS],tmpy=new int[BITVEC_NWORDS];
        int nbits=bitvec_degree(exp); gf2point_set_zero(tmpx,tmpy);
        for(int i=(nbits-1);i>=0;--i){gf2point_double(tmpx,tmpy);if(bitvec_get_bit(exp,i)!=0)gf2point_add(tmpx,tmpy,x,y);}
        gf2point_copy(x,y,tmpx,tmpy);
    }
    static boolean gf2point_on_curve(final int[] x,final int[] y){
        int[] a=new int[BITVEC_NWORDS],b=new int[BITVEC_NWORDS];
        if(gf2point_is_zero(x,y))return false;
        gf2field_mul(a,x,x);gf2field_mul(b,a,x);gf2field_add(a,a,b);gf2field_add(a,a,coeff_b);gf2field_mul(b,y,y);gf2field_add(a,a,b);gf2field_mul(b,x,y);
        return bitvec_equal(a,b);
    }
    static int[] bytes_to_int(byte[] bytes,int offset){int[] v=new int[BITVEC_NWORDS];int p=offset;for(int i=0;i<BITVEC_NWORDS;i++)v[i]=((bytes[p++]&0xff))|((bytes[p++]&0xff)<<8)|((bytes[p++]&0xff)<<16)|((bytes[p++]&0xff)<<24);return v;}
    static void ints_to_bytes(byte[] bytes,int[] ints,int offset){int p=offset;for(int i=0;i<BITVEC_NWORDS;i++){bytes[p++]=(byte)(ints[i]&0xff);bytes[p++]=(byte)((ints[i]>>8)&0xff);bytes[p++]=(byte)((ints[i]>>16)&0xff);bytes[p++]=(byte)((ints[i]>>24)&0xff);}}

    static boolean ecdh_generate_keys(byte[] public_key,byte[] private_key){
        int[] priv=bytes_to_int(private_key,0),pubx=bytes_to_int(public_key,0),puby=bytes_to_int(public_key,BITVEC_NBYTES);
        gf2point_copy(pubx,puby,base_x,base_y);
        if(bitvec_degree(priv)<(CURVE_DEGREE/2))return false;
        int nbits=bitvec_degree(base_order);
        for(int i=(nbits-1);i<(BITVEC_NWORDS*32);++i)bitvec_clr_bit(priv,i);
        gf2point_mul(pubx,puby,priv);
        ints_to_bytes(public_key,pubx,0);ints_to_bytes(public_key,puby,BITVEC_NBYTES);
        return true;
    }
    static boolean ecdh_shared_secret(byte[] private_key,byte[] others_pub,byte[] output){
        int[] priv=bytes_to_int(private_key,0),ox=bytes_to_int(others_pub,0),oy=bytes_to_int(others_pub,BITVEC_NBYTES);
        if(!gf2point_is_zero(ox,oy)&&gf2point_on_curve(ox,oy)){
            for(int i=0;i<(BITVEC_NBYTES*2);++i)output[i]=others_pub[i];
            int nbits=bitvec_degree(base_order);
            for(int i=(nbits-1);i<(BITVEC_NWORDS*32);++i)bitvec_clr_bit(priv,i);
            int[] outx=bytes_to_int(output,0),outy=bytes_to_int(output,BITVEC_NBYTES);
            gf2point_mul(outx,outy,priv);
            ints_to_bytes(output,outx,0);ints_to_bytes(output,outy,BITVEC_NBYTES);
            return true;
        }return false;
    }
    public static byte[] ecdh_generate_public(byte[] privateEC){byte[] pub=new byte[ECC_PUB_KEY_SIZE];if(ecdh_generate_keys(pub,privateEC))return pub;return null;}
    public static byte[] ecdh_generate_shared(byte[] privateEC,byte[] remotePublicEC){byte[] sh=new byte[ECC_PUB_KEY_SIZE];if(ecdh_shared_secret(privateEC,remotePublicEC,sh))return sh;return null;}
}
