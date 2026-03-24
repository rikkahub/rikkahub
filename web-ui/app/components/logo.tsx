import type { ComponentPropsWithRef } from "react";

export default function Logo(props: ComponentPropsWithRef<"svg">) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 1200 1200"
      width="256"
      height="256"
      {...props}
    >
      <rect
        x="70"
        y="70"
        width="1060"
        height="1060"
        rx="250"
        fill="white"
        stroke="#e7e7e7"
        strokeWidth="24"
      />

      <g transform="translate(190 190)" fill="none" stroke="#111111" strokeLinecap="round">
        <circle
          cx="410"
          cy="410"
          r="330"
          strokeWidth="13"
          strokeDasharray="350 116 250 146"
          transform="rotate(-20 410 410)"
        />
        <circle
          cx="410"
          cy="410"
          r="248"
          strokeWidth="15"
          strokeDasharray="246 90 184 96"
          transform="rotate(32 410 410)"
        />
        <circle
          cx="410"
          cy="410"
          r="166"
          strokeWidth="17"
          strokeDasharray="164 62 116 70"
          transform="rotate(112 410 410)"
        />
        <circle
          cx="410"
          cy="410"
          r="86"
          strokeWidth="18"
          strokeDasharray="88 32 62 40"
          transform="rotate(10 410 410)"
        />
        <circle cx="410" cy="410" r="10" fill="#111111" stroke="none" />
      </g>
    </svg>
  );
}
