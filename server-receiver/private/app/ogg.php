<?php
declare(strict_types=1);
// Narrow receiver validator: one complete Ogg Opus stream, mapping family 0,
// stereo. Validates framing, page CRCs, sequence, header and EOS. It does not
// decode speech or prove the claimed speaker identities.
function ogg_crc(string $page): int {
    static $table;
    if ($table === null) {
        $table = [];
        for ($i=0; $i<256; $i++) {
            $r = $i << 24;
            for ($j=0; $j<8; $j++) $r = (($r << 1) ^ (($r & 0x80000000) ? 0x04c11db7 : 0)) & 0xffffffff;
            $table[$i] = $r;
        }
    }
    $crc = 0;
    for ($i=0,$n=strlen($page); $i<$n; $i++) $crc = (($crc << 8) ^ $table[(($crc >> 24) ^ ord($page[$i])) & 255]) & 0xffffffff;
    return $crc;
}
function exact_read($h, int $bytes): string {
    if (!$bytes) return '';
    $s = fread($h, $bytes);
    if ($s === false || strlen($s) !== $bytes) fail(400, 'truncated_ogg');
    return $s;
}
function opus_info(string $path): array {
    $h = fopen($path,'rb'); if (!$h) throw new RuntimeException('audio_read_failed');
    $seq=0; $serial=null; $packet=''; $packets=0; $audioPackets=0; $eos=false; $lastGranule=0; $preskip=0;
    try {
        while (true) {
            $head=fread($h,27);
            if ($head === '') break;
            if ($head === false || strlen($head)!==27) fail(400,'truncated_ogg');
            if ($eos || substr($head,0,4)!=='OggS' || ord($head[4])!==0) fail(400,'invalid_ogg');
            $flags=ord($head[5]); $n=ord($head[26]);
            if (($flags & ~7)!==0 || !$n) fail(400,'invalid_ogg_flags');
            $s=unpack('V',substr($head,14,4))[1]; $p=unpack('V',substr($head,18,4))[1];
            if ($serial===null) $serial=$s;
            if ($s!==$serial || $p!==$seq || (bool)($flags&2)!==($seq===0) || (bool)($flags&1)!==($packet!=='')) fail(400,'invalid_ogg_sequence');
            $laces=exact_read($h,$n); $length=0;
            for($i=0;$i<$n;$i++) $length+=ord($laces[$i]);
            $body=exact_read($h,$length);
            $crc=unpack('V',substr($head,22,4))[1];
            if (ogg_crc(substr_replace($head,"\0\0\0\0",22,4).$laces.$body)!==$crc) fail(400,'ogg_crc_mismatch');
            $off=0;
            for($i=0;$i<$n;$i++) {
                $len=ord($laces[$i]); $packet.=substr($body,$off,$len); $off+=$len;
                if (strlen($packet)>1048576) fail(400,'ogg_packet_too_large');
                if($len===255) continue;
                if($packets===0) {
                    if(strlen($packet)!==19 || substr($packet,0,8)!=='OpusHead' || ord($packet[8])!==1 || ord($packet[9])!==2 || ord($packet[18])!==0 || $seq!==0 || $i!==$n-1) fail(400,'unsupported_opus_profile');
                    $preskip=unpack('v',substr($packet,10,2))[1];
                } elseif($packets===1) {
                    if(strlen($packet)<16 || substr($packet,0,8)!=='OpusTags' || $i!==$n-1) fail(400,'invalid_opus_tags');
                    $vendor=unpack('V',substr($packet,8,4))[1]; $pos=12+$vendor;
                    if($pos+4>strlen($packet)) fail(400,'invalid_opus_tags');
                    $count=unpack('V',substr($packet,$pos,4))[1]; $pos+=4;
                    if($count>10000) fail(400,'invalid_opus_tags');
                    for($j=0;$j<$count;$j++) {
                        if($pos+4>strlen($packet)) fail(400,'invalid_opus_tags');
                        $lenTag=unpack('V',substr($packet,$pos,4))[1]; $pos+=4+$lenTag;
                        if($pos>strlen($packet)) fail(400,'invalid_opus_tags');
                    }
                } else { if($packet==='') fail(400,'empty_opus_packet'); $audioPackets++; }
                $packets++; $packet='';
            }
            $g=unpack('Vlo/Vhi',substr($head,6,8));
            if($g['hi']!==0xffffffff || $g['lo']!==0xffffffff) {
                if($g['hi']>0 || $g['lo']<$lastGranule) fail(400,'invalid_ogg_granule');
                $lastGranule=$g['lo'];
            } elseif($flags&4) fail(400,'invalid_ogg_granule');
            $eos=(bool)($flags&4); $seq++;
            if($eos && $packet!=='') fail(400,'incomplete_ogg_packet');
        }
        if(!$eos || $audioPackets<1 || $packet!=='' || $lastGranule<=$preskip) fail(400,'incomplete_ogg');
        $duration=(int)round(($lastGranule-$preskip)/48);
        if($duration>86400000) fail(400,'audio_too_long');
        return ['channels'=>2,'sample_rate'=>48000,'audio_duration_ms'=>$duration,'pages'=>$seq];
    } finally { fclose($h); }
}
