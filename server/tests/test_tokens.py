from perry_server.auth.tokens import generate_device_token, hash_token, tokens_equal


def test_generate_token_entropy() -> None:
    a = generate_device_token()
    b = generate_device_token()
    assert a != b
    assert len(a) >= 32


def test_hash_is_stable_and_peppered() -> None:
    token = "sample-token"
    h1 = hash_token(token, "pepper-a")
    h2 = hash_token(token, "pepper-a")
    h3 = hash_token(token, "pepper-b")
    assert tokens_equal(h1, h2)
    assert h1 != h3
    assert h1 != token
