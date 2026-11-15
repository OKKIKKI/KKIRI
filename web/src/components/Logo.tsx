// 끼리 심볼: 'ㄲ' = 나란히 달리는 두 대의 차. 뒤차 = 일행(브랜드 보라), 앞차 = 나(글자색). 차량 앱 kk_logo와 같은 모양.
export default function Logo() {
  const car = 'M0,0 H28 L40,14 H54 V30';
  const wheels = 'M3,42 a7,7 0 1,0 14,0 a7,7 0 1,0 -14,0 M37,42 a7,7 0 1,0 14,0 a7,7 0 1,0 -14,0';
  return (
    <div className="brand" role="img" aria-label="KKIRI">
      <svg viewBox="0 0 126 56" xmlns="http://www.w3.org/2000/svg">
        <g transform="translate(6 6)">
          <path d={car} fill="none" stroke="var(--c-brand)" strokeWidth="9" strokeLinecap="round" strokeLinejoin="round" />
          <path d={wheels} fill="var(--c-brand)" />
        </g>
        <g transform="translate(66 6)">
          <path d={car} fill="none" stroke="var(--c-me)" strokeWidth="9" strokeLinecap="round" strokeLinejoin="round" />
          <path d={wheels} fill="var(--c-me)" />
        </g>
      </svg>
    </div>
  );
}
