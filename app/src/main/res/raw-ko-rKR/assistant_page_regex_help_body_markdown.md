# 이 편집기의 기능

Regex 규칙은 채팅 파이프라인의 특정 지점에서 텍스트를 다시 씁니다.

다음과 같은 용도로 사용할 수 있습니다:
- 가져온 SillyTavern 서식을 정리
- 모델에 보내기 전에 이름이나 문장 부호를 정규화
- UI에서만 불필요한 텍스트 숨기기
- user input, AI output, world info, slash commands, reasoning 같은 ST 스타일 배치 지점 지정

## 빠른 시작

1. 규칙에 알기 쉬운 이름을 지정합니다.
2. **Find Regex**에 패턴을 작성합니다.
3. **Replace String**에 출력 내용을 작성합니다.
4. 실제 메시지에 영향을 줄지, UI에만 적용할지, 프롬프트에만 적용할지 결정합니다.
5. 이 규칙이 ST에서 온 것이라면, 타이밍이 원본 스크립트와 맞도록 **ST placements**를 설정합니다.

## 데모 1: 닉네임 변형 통일하기

```text
목표:
"Alicia"와 "Ally"를 "Alice"로 바꾸기

Find Regex:
Alicia|Ally

Replace String:
Alice
```

## 데모 2: 캡처된 그룹을 다시 쓰기 전에 정리하기

```text
목표:
일치한 내용 중 유용한 부분만 남기기

Find Regex:
Name:\s*(.*)

Replace String:
$1

Trim captured strings:
[
]
```

## 중요한 필드

- **Trim captured strings**: 캡처된 그룹을 다시 쓰기 전에, 그 안의 추가 래퍼 텍스트를 제거합니다.
- **RAW / ESCAPED macro substitution**: 매칭 전에 regex 자체 안의 `{{char}}`, `{{user}}` 같은 매크로를 해석합니다.
- **ST placements**: 이것이 비어 있지 않으면, 위의 일반 범위 설정보다 우선 적용됩니다.
- **Run on edit**: 기존 메시지를 수정할 때도 이 규칙을 적용합니다.

> 경험칙: SillyTavern에서 regex를 복사해 온 경우 먼저 ST placements를 설정하세요. 앱 전체에 적용되는 일반 정리 규칙을 만들고 있다면 일반 범위 설정부터 시작하세요.
