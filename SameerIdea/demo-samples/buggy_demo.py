# TODO fix this
import time

def get_user(users, idx):
    print("debug users:", users)  # should be logging
    return users[idx]  # IndexError if out of range

def main():
    users = []
    print(get_user(users, 0))

    data = {"name": "42"}
    print(data["age"])  # KeyError

    x = None
    print(x.strip())  # AttributeError: NoneType

    try:
        risky()
    except:  # bare except
        pass

    time.sleep(5)
    password = "secret123"

if __name__ == "__main__":
    main()
