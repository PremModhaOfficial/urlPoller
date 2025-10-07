from collections import defaultdict
import random


class IpGroup:
    def __init__(self, ips: list[str], pollRate: int):
        self.ips = ips
        self.pollRate = pollRate
        self.tick = 0

    def __str__(self):
        return f"ips = {self.ips} pollRate = {self.pollRate} tick = {self.tick}"

    def __repr__(self):
        return self.__str__()


def randomIps(length=2):
    ret = []
    for _ in range(length):
        ret.append(random.randbytes(8))

    return ret


pollTimes = {i: IpGroup(randomIps(), i) for i in range(100)}


def scheduleThis(polls: dict[int, IpGroup]):
    schedule: dict[int, list[int]] = defaultdict(list)
    for poll in polls.keys():
        ipgroup = polls[poll]
        ipgroup.tick += 1
        nextInterval = ipgroup.tick * ipgroup.pollRate
        mod = (-nextInterval) % 5
        if mod == 0:
            nextClosestPoll = nextInterval
        else:
            nextClosestPoll = mod + nextInterval
        schedule[nextClosestPoll].append(polls[poll].pollRate)

    return schedule


shed = scheduleThis(pollTimes)

for i in shed.keys():
    print(i, shed[i])
