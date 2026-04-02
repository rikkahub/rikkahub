# Lorebook 편집하기

이 시트에서는 하나의 책과 해당 항목 목록을 관리합니다.

## 책 수준 필드

- **Name**: 정리용입니다.
- **Description**: 사람을 위한 선택적 메모입니다.
- **Enabled**: 책 전체를 켜거나 끕니다.
- **Recursive scanning**: 이미 트리거된 항목이 이후 패스에서 더 많은 항목을 활성화할 수 있게 합니다.
- **Token budget**: 이 책에서 삽입할 수 있는 텍스트 양을 제한합니다.

## 권장 워크플로

1. 책을 만듭니다.
2. 재귀적으로 할지 설정합니다.
3. token budget이 필요한지 결정합니다.
4. 항목을 하나씩 추가합니다.
5. 실제 채팅 예시로 테스트합니다.

## 데모 워크플로

```text
Book:
School Life

Use case:
수업, 동아리, 기숙사 또는 시험 주제가 나올 때만 캠퍼스 세부 정보를 삽입합니다.

Suggested setup:
- Recursive scanning: 처음에는 끔
- Token budget: 처음에는 비워 둠
- dorm, student council, exam rules, club culture에 대해 각각 별도 항목 추가
```

## Recursive scanning을 변경해야 하는 경우

하나의 트리거된 항목이 이후 패스에서 관련 항목을 깨워야 할 때만 켭니다.

> 대부분의 책은 단순하게 시작하는 것이 좋습니다: Recursive scanning은 끄고, token budget은 없이 시작한 다음, 필요하면 나중에 더 엄격하게 조정하세요.
