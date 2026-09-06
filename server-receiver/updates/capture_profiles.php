<?php
declare(strict_types=1);
// Server-controlled policy for the pilot Redmi's frozen build-19 capture path.
// Client-supplied app_build/model/role fields cannot enable this compatibility.
return [
    'pilot' => [
        'redmi-note12-01' => [
            'allow_page_aligned_eof' => true,
            'capture_profile' => 'redmi_note12_build19_stereo',
            'evidence' => 'D1 report: 758 complete CRC-valid pages; no EOS; no partial packet',
        ],
    ],
];
