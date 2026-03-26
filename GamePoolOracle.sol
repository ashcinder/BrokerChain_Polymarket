// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

contract PredictionMarket {
    address public officialOracle;

    struct Game {
        uint256 id;
        string description;
        string condition;
        string[] optionNames;
        uint8 optionCount;
        uint256 totalPool;
        mapping(uint8 => uint256) optionPools;
        bool isResolved;
        uint8 winningOption;
        uint256 deadline;
        bool isRefunded;
    }

    uint256 public gameCount;
    mapping(uint256 => Game) public games;
    mapping(uint256 => mapping(address => mapping(uint8 => uint256))) public stakes;

    constructor() {
        officialOracle = msg.sender;
    }

    function createGame(string memory _desc, string memory _condition, string[] memory _optionNames, uint256 _duration) public {
        require(_optionNames.length >= 2, "At least 2 options");
        gameCount++;
        Game storage g = games[gameCount];
        g.id = gameCount;
        g.description = _desc;
        g.condition = _condition;
        g.optionNames = _optionNames;
        g.optionCount = uint8(_optionNames.length);
        g.deadline = block.timestamp + _duration;
    }

    function stakeTokens(uint256 _gameId, uint8 _option) public payable {
        Game storage g = games[_gameId];
        require(block.timestamp < g.deadline, "Expired");
        require(!g.isResolved && !g.isRefunded, "Closed");
        g.optionPools[_option] += msg.value;
        g.totalPool += msg.value;
        stakes[_gameId][msg.sender][_option] += msg.value;
    }

    function resolveGame(uint256 _gameId, uint8 _winningOption, bytes32 _vdfProof) public {
        require(msg.sender == officialOracle, "Not Oracle");
        Game storage g = games[_gameId];
        g.isResolved = true;
        g.winningOption = _winningOption;
    }

    function triggerRefund(uint256 _gameId) public {
        Game storage g = games[_gameId];
        require(block.timestamp > g.deadline && !g.isResolved, "Active");
        g.isRefunded = true;
    }

    function claimReward(uint256 _gameId, uint8 _option) public {
        Game storage g = games[_gameId];
        uint256 userStake = stakes[_gameId][msg.sender][_option];
        require(userStake > 0, "No stake");
        uint256 payout = 0;
        if (g.isRefunded) {
            payout = userStake;
        } else {
            require(g.isResolved && _option == g.winningOption, "Not winner");
            payout = (userStake * g.totalPool) / g.optionPools[g.winningOption];
        }
        stakes[_gameId][msg.sender][_option] = 0;
        payable(msg.sender).transfer(payout);
    }

    // ================= 接口 1：获取基础信息（无变化，不会触发 Stack Too Deep） =================
    function getGameInfo(uint256 _id) public view returns (string memory, string memory, string[] memory, uint8, uint256, bool, uint8, uint256, bool) {
        Game storage g = games[_id];
        return (g.description, g.condition, g.optionNames, g.optionCount, g.totalPool, g.isResolved, g.winningOption, g.deadline, g.isRefunded);
    }

    // ================= 接口 2：核心性能优化！单独获取动态数组，彻底解决 N+1 查询问题 =================
    function getGameExtraData(uint256 _gameId, address _user) public view returns (uint256[] memory pools, uint256[] memory userStakes) {
        Game storage g = games[_gameId];

        // 在内存中分配数组空间
        uint256[] memory _pools = new uint256[](g.optionCount);
        uint256[] memory _userStakes = new uint256[](g.optionCount);

        // 在合约内部一次性循环完毕
        for (uint8 i = 0; i < g.optionCount; i++) {
            _pools[i] = g.optionPools[i];
            _userStakes[i] = stakes[_gameId][_user][i];
        }

        return (_pools, _userStakes);
    }
}